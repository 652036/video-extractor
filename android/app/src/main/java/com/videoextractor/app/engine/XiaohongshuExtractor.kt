package com.videoextractor.app.engine

import com.videoextractor.app.core.HttpClients
import com.videoextractor.app.core.UrlUtils
import com.videoextractor.app.model.ExtractResult
import com.videoextractor.app.model.ExtractedVideo
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

object XiaohongshuExtractor {
    private val INITIAL_STATE_RE = Regex(
        """window\.__INITIAL_STATE__\s*=\s*(\{.*?\})\s*</script>""",
        RegexOption.DOT_MATCHES_ALL,
    )
    private val ORIGIN_VIDEO_KEY_RE = Regex(""""originVideoKey"\s*:\s*"([^"]+)"""")
    private val MASTER_URL_RE = Regex(""""masterUrl"\s*:\s*"(https?:[^"]+)"""")
    private val OG_VIDEO_RE = Regex("""<meta[^>]+property=["']og:video["'][^>]+content=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val OG_TITLE_RE = Regex("""<meta[^>]+property=["']og:title["'][^>]+content=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val OG_IMAGE_RE = Regex("""<meta[^>]+property=["']og:image["'][^>]+content=["']([^"']+)["']""", RegexOption.IGNORE_CASE)

    fun parseHtml(html: String, pageUrl: String): ExtractResult? {
        var title = OG_TITLE_RE.find(html)?.groupValues?.getOrNull(1)?.trim()?.ifBlank { null } ?: "小红书视频"
        var cover = DouyinExtractor.normalizeUrl(OG_IMAGE_RE.find(html)?.groupValues?.getOrNull(1))
        var direct: String? = null

        val stateMatch = INITIAL_STATE_RE.find(html)
        if (stateMatch != null) {
            try {
                // Xiaohongshu emits JS `undefined` values inside __INITIAL_STATE__
                val sanitized = stateMatch.groupValues[1].replace(":undefined", ":null")
                val root = JSONObject(sanitized)
                val extracted = walkXhsState(root)
                if (extracted.title != null) title = extracted.title
                if (extracted.cover != null) cover = extracted.cover
                if (extracted.videoUrl != null) direct = extracted.videoUrl
            } catch (_: Exception) {
                // fallback to regex extraction below
            }
        }

        if (direct == null) {
            val key = ORIGIN_VIDEO_KEY_RE.find(html)?.groupValues?.getOrNull(1)
                ?.replace("\\u002F", "/")
                ?.replace("\\/", "/")
                ?.trimStart('/')
            if (!key.isNullOrBlank()) {
                direct = "https://sns-video-bd.xhscdn.com/$key"
            }
        }

        if (direct == null) {
            direct = DouyinExtractor.normalizeUrl(MASTER_URL_RE.find(html)?.groupValues?.getOrNull(1))
                ?: DouyinExtractor.normalizeUrl(OG_VIDEO_RE.find(html)?.groupValues?.getOrNull(1))
        }

        if (direct == null) return null
        return ExtractResult.Success(
            ExtractedVideo(
                title = title.lines().first().take(120),
                directUrl = direct,
                pageUrl = pageUrl,
                platform = "小红书",
                engine = "xhs-html",
                thumbnailUrl = cover,
                headers = mapOf(
                    "User-Agent" to HttpClients.MOBILE_UA,
                    "Referer" to "https://www.xiaohongshu.com/",
                ),
            )
        )
    }

    private data class XhsFound(val title: String?, val cover: String?, val videoUrl: String?)

    private fun walkXhsState(root: JSONObject): XhsFound {
        var title: String? = null
        var cover: String? = null
        var videoUrl: String? = null

        fun walk(node: Any?, depth: Int) {
            if (node == null || depth > 12) return
            when (node) {
                is JSONObject -> {
                    if (title == null) {
                        val t = node.optString("title").ifBlank { node.optString("desc") }.trim()
                        if (t.length >= 2) title = t
                    }
                    if (videoUrl == null) {
                        val key = node.optString("originVideoKey").trim().trimStart('/')
                        if (key.isNotEmpty() && key.contains("/")) {
                            videoUrl = "https://sns-video-bd.xhscdn.com/$key"
                        }
                    }
                    if (videoUrl == null) {
                        val m = DouyinExtractor.normalizeUrl(node.optString("masterUrl"))
                        if (m != null && (m.contains(".mp4") || m.contains("xhscdn"))) {
                            videoUrl = m
                        }
                    }
                    if (cover == null) {
                        val c = DouyinExtractor.normalizeUrl(node.optString("urlDefault"))
                            ?: DouyinExtractor.normalizeUrl(node.optString("cover"))
                        if (c != null) cover = c
                    }
                    val keys = node.keys()
                    while (keys.hasNext()) {
                        walk(node.opt(keys.next()), depth + 1)
                    }
                }
                is JSONArray -> {
                    for (i in 0 until minOf(node.length(), 25)) {
                        walk(node.opt(i), depth + 1)
                    }
                }
            }
        }
        walk(root, 0)
        return XhsFound(title, cover, videoUrl)
    }

    fun extract(url: String, client: OkHttpClient = HttpClients.default): ExtractResult? {
        if (!UrlUtils.isXiaohongshu(url)) return null
        val reqBuilder = Request.Builder()
            .url(url)
            .header("User-Agent", HttpClients.DESKTOP_UA)
            .header("Accept", "text/html,application/xhtml+xml")
            .header("Referer", "https://www.xiaohongshu.com/")
        HttpClients.webViewCookiesFor(url)?.let { reqBuilder.header("Cookie", it) }

        return try {
            client.newCall(reqBuilder.build()).execute().use { resp ->
                if (!resp.isSuccessful) return null
                parseHtml(resp.body?.string().orEmpty(), resp.request.url.toString())
            }
        } catch (_: Exception) {
            null
        }
    }
}
