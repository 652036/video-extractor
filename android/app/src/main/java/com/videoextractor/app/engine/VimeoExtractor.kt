package com.videoextractor.app.engine

import com.videoextractor.app.core.HttpClients
import com.videoextractor.app.core.UrlUtils
import com.videoextractor.app.model.ExtractResult
import com.videoextractor.app.model.ExtractedVideo
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * Vimeo extractor via `player.vimeo.com/video/{id}/config` (progressive MP4 or HLS).
 */
object VimeoExtractor {

    fun parsePlayerConfig(payload: JSONObject, pageUrl: String): ExtractResult? {
        val videoObj = payload.optJSONObject("video")
        val title = videoObj?.optString("title")?.trim()?.ifBlank { null }?.take(120) ?: "Vimeo 视频"
        val thumbs = videoObj?.optJSONObject("thumbs")
        val cover = listOf("1280", "960", "640", "base").firstNotNullOfOrNull {
            DouyinExtractor.normalizeUrl(thumbs?.optString(it))
        }

        val files = payload.optJSONObject("request")?.optJSONObject("files") ?: return null
        val progressive = files.optJSONArray("progressive")
        var bestUrl: String? = null
        var bestHeight = -1

        if (progressive != null) {
            for (i in 0 until progressive.length()) {
                val item = progressive.optJSONObject(i) ?: continue
                val u = DouyinExtractor.normalizeUrl(item.optString("url")) ?: continue
                val h = item.optInt("height", 0)
                if (bestUrl == null || h > bestHeight) {
                    bestUrl = u
                    bestHeight = h
                }
            }
        }

        if (bestUrl == null) {
            val hlsCdns = files.optJSONObject("hls")?.optJSONObject("cdns")
            val keys = hlsCdns?.keys()
            if (keys != null) {
                while (keys.hasNext()) {
                    val cdn = hlsCdns.optJSONObject(keys.next())
                    val u = DouyinExtractor.normalizeUrl(cdn?.optString("url"))
                    if (u != null) {
                        bestUrl = u
                        break
                    }
                }
            }
        }

        val finalUrl = bestUrl ?: return null
        return ExtractResult.Success(
            ExtractedVideo(
                title = title,
                directUrl = finalUrl,
                pageUrl = pageUrl,
                platform = "Vimeo",
                engine = "vimeo-config",
                ext = if (finalUrl.lowercase().contains(".m3u8")) "m3u8" else "mp4",
                thumbnailUrl = cover,
                headers = mapOf(
                    "User-Agent" to HttpClients.DESKTOP_UA,
                    "Referer" to "https://vimeo.com/",
                ),
            )
        )
    }

    fun extract(url: String, client: OkHttpClient = HttpClients.default): ExtractResult? {
        val vimeoId = UrlUtils.vimeoIdOf(url) ?: return null
        val pageUrl = "https://vimeo.com/$vimeoId"
        val cookies = HttpClients.webViewCookiesFor("https://vimeo.com/")
        val req = Request.Builder()
            .url("https://player.vimeo.com/video/$vimeoId/config")
            .header("User-Agent", HttpClients.DESKTOP_UA)
            .header("Referer", "https://vimeo.com/")
            .header("Accept", "application/json")
            .apply { if (cookies != null) header("Cookie", cookies) }
            .build()

        return try {
            client.newCall(req).execute().use { resp ->
                if (resp.code == 401 || resp.code == 403) {
                    return ExtractResult.NeedsAuth(
                        platform = "Vimeo",
                        pageUrl = pageUrl,
                        message = "Vimeo 限制了匿名访问（HTTP ${resp.code}），可点击「内置浏览器打开」登录后重试",
                    )
                }
                if (!resp.isSuccessful) return null
                parsePlayerConfig(JSONObject(resp.body?.string().orEmpty()), pageUrl)
            }
        } catch (_: Exception) {
            null
        }
    }
}
