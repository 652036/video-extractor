package com.videoextractor.app.engine

import android.content.Context
import com.videoextractor.app.core.UrlUtils
import com.videoextractor.app.model.ExtractResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Coordinates on-device video extraction:
 * 1. Extract URL from share text and enforce VIP/DRM blocklist.
 * 2. Expand short links (including `m.tb.cn` JS redirect) and re-check blocklist.
 * 3. Run platform-specific fast HTTP extractor.
 * 4. Fall back to hidden Android WebView sniffer (uses local mobile IP + CookieManager).
 */
class VideoExtractorEngine(private val appContext: Context) {
    private val webViewSniffer = WebViewSniffer(appContext)

    suspend fun extract(
        rawInput: String,
        onStageUpdate: (String) -> Unit = {},
    ): ExtractResult {
        val rawUrl = UrlUtils.extractUrl(rawInput)
            ?: return ExtractResult.Failure("未在输入内容中识别到 http(s) 链接，请粘贴完整分享口令或视频链接")

        UrlUtils.checkBlocked(rawUrl)?.let { return ExtractResult.Blocked(it) }

        onStageUpdate("正在展开短链并识别平台…")
        val resolvedUrl = withContext(Dispatchers.IO) {
            UrlUtils.expandShareUrl(rawUrl)
        }
        UrlUtils.checkBlocked(resolvedUrl)?.let { return ExtractResult.Blocked(it) }

        val platform = UrlUtils.platformOf(resolvedUrl)
        onStageUpdate("已识别平台：$platform，正在直连解析…")

        val fastResult: ExtractResult? = withContext(Dispatchers.IO) {
            when {
                UrlUtils.isDouyin(resolvedUrl) -> DouyinExtractor.extract(resolvedUrl)
                UrlUtils.isKuaishou(resolvedUrl) -> KuaishouExtractor.extract(resolvedUrl)
                UrlUtils.isXianyu(resolvedUrl) -> XianyuExtractor.extract(resolvedUrl)
                UrlUtils.isXiaohongshu(resolvedUrl) -> XiaohongshuExtractor.extract(resolvedUrl)
                UrlUtils.isBilibili(resolvedUrl) -> BilibiliExtractor.extract(resolvedUrl)
                UrlUtils.isYouTube(resolvedUrl) -> YouTubeExtractor.extract(resolvedUrl)
                UrlUtils.isTikTok(resolvedUrl) -> TikTokExtractor.extract(resolvedUrl)
                UrlUtils.isXTwitter(resolvedUrl) -> XTwitterExtractor.extract(resolvedUrl)
                UrlUtils.isInstagram(resolvedUrl) -> InstagramExtractor.extract(resolvedUrl)
                UrlUtils.isFacebook(resolvedUrl) -> FacebookExtractor.extract(resolvedUrl)
                UrlUtils.isReddit(resolvedUrl) -> RedditExtractor.extract(resolvedUrl)
                UrlUtils.isVimeo(resolvedUrl) -> VimeoExtractor.extract(resolvedUrl)
                else -> GenericHtmlExtractor.extract(resolvedUrl)
            }
        }

        when (fastResult) {
            is ExtractResult.Success,
            is ExtractResult.NoVideo,
            is ExtractResult.Blocked -> return fastResult
            else -> Unit
        }

        // Fall back to passive on-device WebView sniffer (real Chrome engine + mobile IP + local cookies).
        onStageUpdate("直连未命中，正在启动内置 WebView 嗅探…")
        val sniffResult = webViewSniffer.sniff(resolvedUrl)
        if (sniffResult != null) {
            return sniffResult
        }

        if (fastResult is ExtractResult.NeedsAuth) {
            return fastResult
        }

        return ExtractResult.Failure(
            message = "自动解析未能提取到直链。你可以点击下方「内置浏览器打开」，在页面加载播放或登录后自动嗅探。",
            pageUrl = SniffHelper.buildSniffTargetUrl(resolvedUrl),
            platform = platform,
        )
    }
}
