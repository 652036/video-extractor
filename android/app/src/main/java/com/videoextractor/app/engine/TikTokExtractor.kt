package com.videoextractor.app.engine

import com.videoextractor.app.core.HttpClients
import com.videoextractor.app.core.UrlUtils
import com.videoextractor.app.model.ExtractResult
import com.videoextractor.app.model.ExtractedVideo
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * TikTok HTML hydration extractor (`__UNIVERSAL_DATA_FOR_REHYDRATION__` / `SIGI_STATE`).
 * Falls back to on-device WebView sniffer when JS/cookies are required.
 */
object TikTokExtractor {
    private val UNIVERSAL_RE = Regex(
        """<script[^>]+id=["']__UNIVERSAL_DATA_FOR_REHYDRATION__["'][^>]*>\s*(\{.*?\})\s*</script>""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
    )
    private val SIGI_RE = Regex(
        """<script[^>]+id=["']SIGI_STATE["'][^>]*>\s*(\{.*?\})\s*</script>""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
    )

    fun parseHtml(html: String, pageUrl: String): ExtractResult? {
        UNIVERSAL_RE.find(html)?.groupValues?.getOrNull(1)?.let { raw ->
            try {
                val root = JSONObject(raw)
                val scope = root.optJSONObject("__DEFAULT_SCOPE__")
                val detail = scope?.optJSONObject("webapp.video-detail")
                val itemStruct = detail?.optJSONObject("itemInfo")?.optJSONObject("itemStruct")
                if (itemStruct != null) {
                    parseItemStruct(itemStruct, pageUrl)?.let { return it }
                }
            } catch (_: Exception) {
                // fall through
            }
        }

        SIGI_RE.find(html)?.groupValues?.getOrNull(1)?.let { raw ->
            try {
                val root = JSONObject(raw)
                val itemModule = root.optJSONObject("ItemModule")
                val keys = itemModule?.keys()
                if (keys != null && keys.hasNext()) {
                    val item = itemModule.optJSONObject(keys.next())
                    if (item != null) {
                        parseItemStruct(item, pageUrl)?.let { return it }
                    }
                }
            } catch (_: Exception) {
                // fall through
            }
        }
        return null
    }

    private fun parseItemStruct(item: JSONObject, pageUrl: String): ExtractResult? {
        val video = item.optJSONObject("video") ?: return null
        val direct = DouyinExtractor.normalizeUrl(video.optString("playAddr"))
            ?: DouyinExtractor.normalizeUrl(video.optString("downloadAddr"))
            ?: run {
                val br = video.optJSONArray("bitrateInfo")
                var found: String? = null
                if (br != null) {
                    for (i in 0 until br.length()) {
                        val urls = br.optJSONObject(i)?.optJSONObject("PlayAddr")?.optJSONArray("UrlList")
                        if (urls != null && urls.length() > 0) {
                            found = DouyinExtractor.normalizeUrl(urls.optString(0))
                            if (found != null) break
                        }
                    }
                }
                found
            }
            ?: return null

        val title = item.optString("desc").trim().ifBlank { "TikTok 视频" }.lines().first().take(120)
        val cover = DouyinExtractor.normalizeUrl(video.optString("cover"))
            ?: DouyinExtractor.normalizeUrl(video.optString("originCover"))

        return ExtractResult.Success(
            ExtractedVideo(
                title = title,
                directUrl = direct,
                pageUrl = pageUrl,
                platform = "TikTok",
                engine = "tiktok-html",
                thumbnailUrl = cover,
                headers = mapOf(
                    "User-Agent" to HttpClients.DESKTOP_UA,
                    "Referer" to "https://www.tiktok.com/",
                ),
            )
        )
    }

    fun extract(url: String, client: OkHttpClient = HttpClients.default): ExtractResult? {
        if (!UrlUtils.isTikTok(url)) return null
        val reqBuilder = Request.Builder()
            .url(url)
            .header("User-Agent", HttpClients.DESKTOP_UA)
            .header("Accept", "text/html,application/xhtml+xml")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Referer", "https://www.tiktok.com/")
        HttpClients.webViewCookiesFor("https://www.tiktok.com/")?.let { reqBuilder.header("Cookie", it) }

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
