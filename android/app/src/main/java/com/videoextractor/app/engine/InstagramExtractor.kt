package com.videoextractor.app.engine

import com.videoextractor.app.core.HttpClients
import com.videoextractor.app.core.UrlUtils
import com.videoextractor.app.model.ExtractResult
import com.videoextractor.app.model.ExtractedVideo
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Instagram public post / Reel extractor (embed captioned HTML + page HTML with local cookies).
 * Falls back to NeedsAuth / WebView sniffer when Instagram gates content behind login.
 */
object InstagramExtractor {
    private val VIDEO_URL_RE = Regex(""""video_url"\s*:\s*"(https?:[^"]+)"""")
    private val PLAYABLE_HD_RE = Regex(""""playable_url_quality_hd"\s*:\s*"(https?:[^"]+)"""")
    private val PLAYABLE_RE = Regex(""""playable_url"\s*:\s*"(https?:[^"]+)"""")
    private val CONTENT_URL_RE = Regex(""""contentUrl"\s*:\s*"(https?:[^"]+\.mp4[^"]*)"""")
    private val OG_VIDEO_RE = Regex("""<meta[^>]+property=["']og:video["'][^>]+content=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val OG_TITLE_RE = Regex("""<meta[^>]+property=["']og:title["'][^>]+content=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val OG_IMAGE_RE = Regex("""<meta[^>]+property=["']og:image["'][^>]+content=["']([^"']+)["']""", RegexOption.IGNORE_CASE)

    fun parseHtml(html: String, pageUrl: String): ExtractResult? {
        val rawDirect = PLAYABLE_HD_RE.find(html)?.groupValues?.getOrNull(1)
            ?: VIDEO_URL_RE.find(html)?.groupValues?.getOrNull(1)
            ?: PLAYABLE_RE.find(html)?.groupValues?.getOrNull(1)
            ?: CONTENT_URL_RE.find(html)?.groupValues?.getOrNull(1)
            ?: OG_VIDEO_RE.find(html)?.groupValues?.getOrNull(1)

        val direct = DouyinExtractor.normalizeUrl(rawDirect)
        if (direct != null) {
            val title = OG_TITLE_RE.find(html)?.groupValues?.getOrNull(1)?.trim()?.ifBlank { null } ?: "Instagram 视频"
            val cover = DouyinExtractor.normalizeUrl(OG_IMAGE_RE.find(html)?.groupValues?.getOrNull(1))
            return ExtractResult.Success(
                ExtractedVideo(
                    title = title.lines().first().take(120),
                    directUrl = direct,
                    pageUrl = pageUrl,
                    platform = "Instagram",
                    engine = "instagram-html",
                    thumbnailUrl = cover,
                    headers = mapOf(
                        "User-Agent" to HttpClients.DESKTOP_UA,
                        "Referer" to "https://www.instagram.com/",
                    ),
                )
            )
        }

        val low = html.lowercase()
        if (low.contains("/accounts/login/") || low.contains("login_required")) {
            return ExtractResult.NeedsAuth(
                platform = "Instagram",
                pageUrl = pageUrl,
                message = "Instagram 需要登录后查看，可点击「内置浏览器打开」在本机登录后直接嗅探",
            )
        }
        return null
    }

    fun extract(url: String, client: OkHttpClient = HttpClients.default): ExtractResult? {
        if (!UrlUtils.isInstagram(url)) return null
        val shortcode = UrlUtils.instagramShortcodeOf(url)
        val cookies = HttpClients.webViewCookiesFor("https://www.instagram.com/")

        val targets = buildList {
            if (shortcode != null) {
                add("https://www.instagram.com/p/$shortcode/embed/captioned/")
            }
            add(url)
        }

        var lastAuth: ExtractResult.NeedsAuth? = null
        for (target in targets) {
            val req = Request.Builder()
                .url(target)
                .header("User-Agent", HttpClients.DESKTOP_UA)
                .header("Accept", "text/html,application/xhtml+xml")
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Referer", "https://www.instagram.com/")
                .apply { if (cookies != null) header("Cookie", cookies) }
                .build()
            val res = try {
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) null
                    else parseHtml(resp.body?.string().orEmpty(), url)
                }
            } catch (_: Exception) {
                null
            }
            when (res) {
                is ExtractResult.Success -> return res
                is ExtractResult.NeedsAuth -> lastAuth = res
                else -> continue
            }
        }
        return lastAuth
    }
}
