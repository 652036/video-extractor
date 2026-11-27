package com.videoextractor.app.engine

import com.videoextractor.app.core.HttpClients
import com.videoextractor.app.core.UrlUtils
import com.videoextractor.app.model.ExtractResult
import com.videoextractor.app.model.ExtractedVideo
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

object KuaishouExtractor {
    private val STATE_RE = Regex(
        """window\.(?:__APOLLO_STATE__|INIT_STATE|__INITIAL_STATE__)\s*=\s*(\{.*?\})\s*;?\s*</script>""",
        RegexOption.DOT_MATCHES_ALL,
    )
    private val MP4_RE = Regex("""https?://[^\s"'<>\\]+?\.mp4(?:\?[^\s"'<>\\]*)?""", RegexOption.IGNORE_CASE)
    private val REJECT_WORDS = listOf("preview", "watermark", "logo", "avatar", "head", "gif")

    fun parseShareHtml(html: String, pageUrl: String): ExtractResult? {
        var title = "快手视频"
        var cover: String? = null
        val candidates = mutableListOf<String>()

        for (m in STATE_RE.findAll(html)) {
            try {
                val root = JSONObject(m.groupValues[1])
                walkState(root, candidates, onTitle = { if (title == "快手视频") title = it }, onCover = { if (cover == null) cover = it })
            } catch (_: Exception) {
                // ignore malformed state block
            }
        }

        if (candidates.isEmpty()) {
            for (m in MP4_RE.findAll(html)) {
                DouyinExtractor.normalizeUrl(m.value)?.let { candidates.add(it) }
            }
        }

        val best = rankMediaUrls(candidates).firstOrNull() ?: return null
        return ExtractResult.Success(
            ExtractedVideo(
                title = title.lines().first().take(120),
                directUrl = best,
                pageUrl = pageUrl,
                platform = "快手",
                engine = "kuaishou-share",
                thumbnailUrl = cover,
                headers = mapOf(
                    "User-Agent" to HttpClients.MOBILE_UA,
                    "Referer" to "https://www.kuaishou.com/",
                ),
            )
        )
    }

    fun rankMediaUrls(urls: List<String>): List<String> {
        return urls
            .mapNotNull { DouyinExtractor.normalizeUrl(it) }
            .distinct()
            .filter { u ->
                val low = u.lowercase()
                (low.contains(".mp4") || low.contains(".m3u8")) &&
                    !low.contains("avatar") &&
                    !low.contains("static.")
            }
            .sortedWith(
                compareBy<String>(
                    { u -> if (REJECT_WORDS.any { u.lowercase().contains(it) }) 1 else 0 },
                    { u -> if (u.lowercase().contains(".m3u8")) 1 else 0 },
                    { u -> if (u.lowercase().contains("upic") || u.lowercase().contains("kwaicdn")) 0 else 1 },
                )
            )
    }

    private fun walkState(
        obj: Any?,
        out: MutableList<String>,
        onTitle: (String) -> Unit,
        onCover: (String) -> Unit,
        depth: Int = 0,
    ) {
        if (obj == null || depth > 10) return
        when (obj) {
            is JSONObject -> {
                for (k in listOf("caption", "title", "desc")) {
                    val v = obj.optString(k).trim()
                    if (v.length >= 2) onTitle(v)
                }
                for (k in listOf("coverUrl", "poster", "thumbnailUrl")) {
                    val v = DouyinExtractor.normalizeUrl(obj.optString(k))
                    if (v != null) onCover(v)
                }
                for (k in listOf("photoUrl", "playUrl", "mp4Url", "srcNoMark", "url")) {
                    val v = DouyinExtractor.normalizeUrl(obj.optString(k))
                    if (v != null && (v.lowercase().contains(".mp4") || v.lowercase().contains(".m3u8"))) {
                        out.add(v)
                    }
                }
                val keys = obj.keys()
                while (keys.hasNext()) {
                    walkState(obj.opt(keys.next()), out, onTitle, onCover, depth + 1)
                }
            }
            is JSONArray -> {
                for (i in 0 until minOf(obj.length(), 30)) {
                    walkState(obj.opt(i), out, onTitle, onCover, depth + 1)
                }
            }
        }
    }

    fun extract(url: String, client: OkHttpClient = HttpClients.default): ExtractResult? {
        if (!UrlUtils.isKuaishou(url)) return null
        val target = UrlUtils.kuaishouIdOf(url)?.let { "https://www.kuaishou.com/short-video/$it" } ?: url
        val reqBuilder = Request.Builder()
            .url(target)
            .header("User-Agent", HttpClients.MOBILE_UA)
            .header("Accept", "text/html,application/xhtml+xml")
            .header("Referer", "https://www.kuaishou.com/")
        HttpClients.webViewCookiesFor(target)?.let { reqBuilder.header("Cookie", it) }

        return try {
            client.newCall(reqBuilder.build()).execute().use { resp ->
                if (!resp.isSuccessful) return null
                parseShareHtml(resp.body?.string().orEmpty(), url)
            }
        } catch (_: Exception) {
            null
        }
    }
}
