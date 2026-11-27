package com.videoextractor.app.engine

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.videoextractor.app.core.HttpClients
import com.videoextractor.app.core.UrlUtils
import com.videoextractor.app.model.ExtractResult
import com.videoextractor.app.model.ExtractedVideo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

object SniffHelper {
    private val REJECT_WORDS = listOf(
        "punish", "captcha", "passport.", "mini_login", "bixi.alicdn",
        "favicon", ".js", ".css", ".png", ".jpg", ".jpeg", ".webp", ".svg", ".woff",
        "avatar", "logo", "splash", "ad_", "/ads/",
    )

    fun looksLikeMediaUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val low = url.lowercase()
        if (!low.startsWith("http://") && !low.startsWith("https://")) return false
        if (REJECT_WORDS.any { low.contains(it) }) return false
        return low.contains(".mp4") ||
            low.contains(".m3u8") ||
            low.contains("/aweme/v1/play") ||
            low.contains("video_mp4") ||
            low.contains("cloud.video.taobao.com") ||
            low.contains("tbvideo") ||
            low.contains("video.goofish") ||
            (low.contains("xhscdn.com") && low.contains("stream")) ||
            (low.contains("bilivideo") && (low.contains(".mp4") || low.contains(".m4s"))) ||
            (low.contains("googlevideo.com/videoplayback") && low.contains("mime=video")) ||
            low.contains("tiktokcdn.com/video/tos") ||
            low.contains("video.twimg.com/") ||
            (low.contains("cdninstagram.com") && low.contains("mp4")) ||
            (low.contains("fbcdn.net") && low.contains("mp4")) ||
            low.contains("v.redd.it/") ||
            low.contains("vimeocdn.com/")
    }

    fun isAuthWallUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val low = url.lowercase()
        return low.contains("passport.goofish") ||
            low.contains("mini_login") ||
            low.contains("bixi.alicdn.com/punish") ||
            low.contains("login.taobao.com") ||
            low.contains("instagram.com/accounts/login")
    }

    fun defaultRefererFor(pageUrl: String): String {
        return when {
            UrlUtils.isDouyin(pageUrl) -> "https://www.iesdouyin.com/"
            UrlUtils.isXianyu(pageUrl) -> "https://www.goofish.com/"
            UrlUtils.isKuaishou(pageUrl) -> "https://www.kuaishou.com/"
            UrlUtils.isXiaohongshu(pageUrl) -> "https://www.xiaohongshu.com/"
            UrlUtils.isBilibili(pageUrl) -> "https://www.bilibili.com/"
            UrlUtils.isYouTube(pageUrl) -> "https://www.youtube.com/"
            UrlUtils.isTikTok(pageUrl) -> "https://www.tiktok.com/"
            UrlUtils.isXTwitter(pageUrl) -> "https://x.com/"
            UrlUtils.isInstagram(pageUrl) -> "https://www.instagram.com/"
            UrlUtils.isFacebook(pageUrl) -> "https://www.facebook.com/"
            UrlUtils.isReddit(pageUrl) -> "https://www.reddit.com/"
            UrlUtils.isVimeo(pageUrl) -> "https://vimeo.com/"
            else -> pageUrl
        }
    }

    fun buildSniffTargetUrl(canonicalUrl: String): String {
        UrlUtils.awemeIdOf(canonicalUrl)?.let {
            return "https://www.iesdouyin.com/share/video/$it/"
        }
        UrlUtils.xianyuItemIdOf(canonicalUrl)?.let {
            return "https://h5.m.goofish.com/item?id=$it"
        }
        return canonicalUrl
    }

    /**
     * Passive JS hook injected into WebView pages to report `<video>` sources and
     * public MTOP JSON responses (e.g. Xianyu detail/video query) back to Kotlin.
     */
    const val HOOK_JS = """
        (function() {
          if (window.__veHooked) return;
          window.__veHooked = true;
          function report(type, val) {
            try { if (window.VEBridge && val) window.VEBridge.onEvent(type, String(val)); } catch (e) {}
          }
          function scanVideos() {
            try {
              var vids = document.querySelectorAll('video, source');
              for (var i = 0; i < vids.length; i++) {
                var s = vids[i].currentSrc || vids[i].src || vids[i].getAttribute('src');
                if (s && s.indexOf('http') === 0) report('video', s);
              }
            } catch (e) {}
          }
          var origFetch = window.fetch;
          if (origFetch) {
            window.fetch = function() {
              var reqUrl = (arguments[0] && arguments[0].url) ? arguments[0].url : String(arguments[0] || '');
              return origFetch.apply(this, arguments).then(function(resp) {
                try {
                  if (reqUrl.indexOf('mtop.taobao.idle') !== -1 || reqUrl.indexOf('mtop.idle.cloud.video') !== -1) {
                    resp.clone().text().then(function(txt) { report('mtop', txt); }).catch(function(){});
                  }
                } catch (e) {}
                return resp;
              });
            };
          }
          var origOpen = XMLHttpRequest.prototype.open;
          var origSend = XMLHttpRequest.prototype.send;
          XMLHttpRequest.prototype.open = function(m, u) {
            this.__veUrl = String(u || '');
            return origOpen.apply(this, arguments);
          };
          XMLHttpRequest.prototype.send = function() {
            this.addEventListener('load', function() {
              try {
                var u = this.__veUrl || '';
                if (u.indexOf('mtop.taobao.idle') !== -1 || u.indexOf('mtop.idle.cloud.video') !== -1) {
                  report('mtop', this.responseText || '');
                }
              } catch (e) {}
            });
            return origSend.apply(this, arguments);
          };
          setInterval(scanVideos, 1200);
          scanVideos();
        })();
    """
}

class WebViewSniffer(private val appContext: Context) {

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun sniff(canonicalUrl: String, timeoutMs: Long = 16_000L): ExtractResult? {
        val targetUrl = SniffHelper.buildSniffTargetUrl(canonicalUrl)
        val platform = UrlUtils.platformOf(canonicalUrl)
        val deferred = CompletableDeferred<ExtractResult?>()
        var webView: WebView? = null

        return try {
            withContext(Dispatchers.Main) {
                CookieManager.getInstance().setAcceptCookie(true)
                val wv = WebView(appContext).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.mediaPlaybackRequiresUserGesture = false
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                    settings.userAgentString = if (UrlUtils.isDouyin(canonicalUrl)) {
                        HttpClients.IPHONE_UA
                    } else {
                        HttpClients.MOBILE_UA
                    }
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                }
                webView = wv

                var pageTitle: String? = null

                fun completeVideo(rawUrl: String, engineTag: String, titleOverride: String? = null, thumb: String? = null) {
                    if (deferred.isCompleted) return
                    val clean = if (UrlUtils.isDouyin(canonicalUrl)) DouyinExtractor.dewatermark(rawUrl) else rawUrl
                    deferred.complete(
                        ExtractResult.Success(
                            ExtractedVideo(
                                title = (titleOverride ?: pageTitle)?.trim()?.ifBlank { null }?.take(120)
                                    ?: "${platform}视频",
                                directUrl = clean,
                                pageUrl = canonicalUrl,
                                platform = platform,
                                engine = engineTag,
                                thumbnailUrl = thumb,
                                headers = mapOf(
                                    "User-Agent" to wv.settings.userAgentString,
                                    "Referer" to SniffHelper.defaultRefererFor(canonicalUrl),
                                ),
                            )
                        )
                    )
                }

                val bridge = object {
                    @JavascriptInterface
                    fun onEvent(type: String, value: String) {
                        if (deferred.isCompleted) return
                        when (type) {
                            "video" -> {
                                if (SniffHelper.looksLikeMediaUrl(value)) {
                                    completeVideo(value, "webview-dom")
                                }
                            }
                            "mtop" -> {
                                try {
                                    val json = JSONObject(value)
                                    val vq = XianyuExtractor.parseVideoQuery(json)
                                    if (!vq.isNullOrBlank()) {
                                        completeVideo(vq, "webview-mtop")
                                        return
                                    }
                                    val parsed = XianyuExtractor.parseDetailPayload(json, canonicalUrl)
                                    when (val res = parsed.result) {
                                        is ExtractResult.Success -> deferred.complete(
                                            ExtractResult.Success(res.video.copy(engine = "webview-mtop"))
                                        )
                                        is ExtractResult.NoVideo -> deferred.complete(res)
                                        is ExtractResult.NeedsAuth -> deferred.complete(res)
                                        else -> Unit
                                    }
                                } catch (_: Exception) {
                                    // ignore non-JSON
                                }
                            }
                        }
                    }
                }
                wv.addJavascriptInterface(bridge, "VEBridge")

                wv.webChromeClient = object : WebChromeClient() {
                    override fun onReceivedTitle(view: WebView?, title: String?) {
                        if (!title.isNullOrBlank()) pageTitle = title
                    }
                }

                wv.webViewClient = object : WebViewClient() {
                    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                        super.onPageStarted(view, url, favicon)
                        if (SniffHelper.isAuthWallUrl(url) && !deferred.isCompleted) {
                            deferred.complete(
                                ExtractResult.NeedsAuth(
                                    platform = platform,
                                    pageUrl = targetUrl,
                                    message = "$platform 需要登录或安全验证，请点击「内置浏览器打开」在手机本地完成后再提取",
                                )
                            )
                            return
                        }
                        view?.evaluateJavascript(SniffHelper.HOOK_JS, null)
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        view?.evaluateJavascript(SniffHelper.HOOK_JS, null)
                    }

                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest?,
                    ): WebResourceResponse? {
                        val reqUrl = request?.url?.toString()
                        if (!reqUrl.isNullOrBlank() && !deferred.isCompleted) {
                            if (SniffHelper.looksLikeMediaUrl(reqUrl)) {
                                completeVideo(reqUrl, "webview-sniff")
                            }
                        }
                        return super.shouldInterceptRequest(view, request)
                    }
                }

                wv.loadUrl(targetUrl)
            }

            withTimeoutOrNull(timeoutMs) { deferred.await() }
        } finally {
            withContext(Dispatchers.Main) {
                webView?.apply {
                    stopLoading()
                    removeJavascriptInterface("VEBridge")
                    loadUrl("about:blank")
                    destroy()
                }
            }
        }
    }
}
