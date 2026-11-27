package com.videoextractor.app.engine

import com.videoextractor.app.core.HttpClients
import com.videoextractor.app.core.UrlUtils
import com.videoextractor.app.model.ExtractResult
import com.videoextractor.app.model.ExtractedVideo
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Facebook public video / Reel extractor.
 * Extracts `browser_native_hd_url` / `playable_url_quality_hd` / `browser_native_sd_url` / `og:video`.
 */
object FacebookExtractor {
    private val HD_PATTERNS = listOf(
        Regex(""""browser_native_hd_url"\s*:\s*"(https?:[^"]+)""""),
        Regex(""""playable_url_quality_hd"\s*:\s*"(https?:[^"]+)""""),
        Regex(""""hd_src_no_ratelimit"\s*:\s*"(https?:[^"]+)""""),
        Regex(""""hd_src"\s*:\s*"(https?:[^"]+)""""),
    )
    private val SD_PATTERNS = listOf(
        Regex(""""browser_native_sd_url"\s*:\s*"(https?:[^"]+)""""),
        Regex(""""playable_url"\s*:\s*"(https?:[^"]+)""""),
        Regex(""""sd_src_no_ratelimit"\s*:\s*"(https?:[^"]+)""""),
        Regex(""""sd_src"\s*:\s*"(https?:[^"]+)""""),
    )
    private val OG_VIDEO_RE = Regex("""<meta[^>]+property=["']og:video(?::secure_url)?["'][^>]+content=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val OG_TITLE_RE = Regex("""<meta[^>]+property=["']og:title["'][^>]+content=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val OG_IMAGE_RE = Regex("""<meta[^>]+property=["']og:image["'][^>]+content=["']([^"']+)["']""", RegexOption.IGNORE_CASE)

    fun parseHtml(html: String, pageUrl: String): ExtractResult? {
        val rawDirect = HD_PATTERNS.firstNotNullOfOrNull { it.find(html)?.groupValues?.getOrNull(1) }
            ?: SD_PATTERNS.firstNotNullOfOrNull { it.find(html)?.groupValues?.getOrNull(1) }
            ?: OG_VIDEO_RE.find(html)?.groupValues?.getOrNull(1)

        val direct = DouyinExtractor.normalizeUrl(rawDirect) ?: return null
        val title = OG_TITLE_RE.find(html)?.groupValues?.getOrNull(1)?.trim()?.ifBlank { null } ?: "Facebook 视频"
        val cover = DouyinExtractor.normalizeUrl(OG_IMAGE_RE.find(html)?.groupValues?.getOrNull(1))

        return ExtractResult.Success(
            ExtractedVideo(
                title = title.lines().first().take(120),
                directUrl = direct,
                pageUrl = pageUrl,
                platform = "Facebook",
                engine = "facebook-html",
                thumbnailUrl = cover,
                headers = mapOf(
                    "User-Agent" to HttpClients.DESKTOP_UA,
                    "Referer" to "https://www.facebook.com/",
                ),
            )
        )
    }

    fun extract(url: String, client: OkHttpClient = HttpClients.default): ExtractResult? {
        if (!UrlUtils.isFacebook(url)) return null
        val cookies = HttpClients.webViewCookiesFor("https://www.facebook.com/")
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", HttpClients.DESKTOP_UA)
            .header("Accept", "text/html,application/xhtml+xml")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Sec-Fetch-Mode", "navigate")
            .apply { if (cookies != null) header("Cookie", cookies) }
            .build()

        return try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                parseHtml(resp.body?.string().orEmpty(), resp.request.url.toString())
            }
        } catch (_: Exception) {
            null
        }
    }
}
