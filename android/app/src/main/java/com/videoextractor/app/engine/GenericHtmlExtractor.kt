package com.videoextractor.app.engine

import com.videoextractor.app.core.HttpClients
import com.videoextractor.app.core.UrlUtils
import com.videoextractor.app.model.ExtractResult
import com.videoextractor.app.model.ExtractedVideo
import okhttp3.OkHttpClient
import okhttp3.Request

object GenericHtmlExtractor {
    private val OG_VIDEO_RE = Regex(
        """<meta[^>]+(?:property|name)=["'](?:og:video:secure_url|og:video:url|og:video|twitter:player:stream)["'][^>]+content=["']([^"']+)["']""",
        RegexOption.IGNORE_CASE,
    )
    private val OG_TITLE_RE = Regex(
        """<meta[^>]+(?:property|name)=["']og:title["'][^>]+content=["']([^"']+)["']""",
        RegexOption.IGNORE_CASE,
    )
    private val TITLE_TAG_RE = Regex("""<title[^>]*>([^<]+)</title>""", RegexOption.IGNORE_CASE)
    private val OG_IMAGE_RE = Regex(
        """<meta[^>]+(?:property|name)=["']og:image["'][^>]+content=["']([^"']+)["']""",
        RegexOption.IGNORE_CASE,
    )
    private val VIDEO_SRC_RE = Regex("""<(?:video|source)[^>]+src=["']([^"']+\.(?:mp4|m3u8)[^"']*)["']""", RegexOption.IGNORE_CASE)
    private val JSON_LD_CONTENT_URL_RE = Regex(""""contentUrl"\s*:\s*"(https?://[^"]+\.(?:mp4|m3u8)[^"]*)"""", RegexOption.IGNORE_CASE)

    fun parseHtml(html: String, pageUrl: String): ExtractResult? {
        val videoUrl = DouyinExtractor.normalizeUrl(OG_VIDEO_RE.find(html)?.groupValues?.getOrNull(1))
            ?: DouyinExtractor.normalizeUrl(VIDEO_SRC_RE.find(html)?.groupValues?.getOrNull(1))
            ?: DouyinExtractor.normalizeUrl(JSON_LD_CONTENT_URL_RE.find(html)?.groupValues?.getOrNull(1))
            ?: return null

        val low = videoUrl.lowercase()
        if (!low.contains(".mp4") && !low.contains(".m3u8") && !low.contains("video")) {
            return null
        }

        val title = OG_TITLE_RE.find(html)?.groupValues?.getOrNull(1)?.trim()
            ?: TITLE_TAG_RE.find(html)?.groupValues?.getOrNull(1)?.trim()
            ?: "网页视频"
        val cover = DouyinExtractor.normalizeUrl(OG_IMAGE_RE.find(html)?.groupValues?.getOrNull(1))

        return ExtractResult.Success(
            ExtractedVideo(
                title = title.lines().first().take(120),
                directUrl = videoUrl,
                pageUrl = pageUrl,
                platform = UrlUtils.platformOf(pageUrl),
                engine = "html-meta",
                thumbnailUrl = cover,
                headers = mapOf(
                    "User-Agent" to HttpClients.MOBILE_UA,
                    "Referer" to pageUrl,
                ),
            )
        )
    }

    fun extract(url: String, client: OkHttpClient = HttpClients.default): ExtractResult? {
        val reqBuilder = Request.Builder()
            .url(url)
            .header("User-Agent", HttpClients.MOBILE_UA)
            .header("Accept", "text/html,application/xhtml+xml")
        HttpClients.webViewCookiesFor(url)?.let { reqBuilder.header("Cookie", it) }

        return try {
            client.newCall(reqBuilder.build()).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val contentType = resp.header("Content-Type").orEmpty().lowercase()
                if (contentType.startsWith("video/")) {
                    return ExtractResult.Success(
                        ExtractedVideo(
                            title = "直链视频",
                            directUrl = resp.request.url.toString(),
                            pageUrl = url,
                            platform = UrlUtils.platformOf(url),
                            engine = "direct-media",
                            headers = mapOf("User-Agent" to HttpClients.MOBILE_UA),
                        )
                    )
                }
                if (!contentType.contains("html") && !contentType.contains("xml")) return null
                parseHtml(resp.body?.string()?.take(256_000).orEmpty(), resp.request.url.toString())
            }
        } catch (_: Exception) {
            null
        }
    }
}
