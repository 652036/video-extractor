package com.videoextractor.app.engine

import com.videoextractor.app.core.HttpClients
import com.videoextractor.app.core.UrlUtils
import com.videoextractor.app.model.ExtractResult
import com.videoextractor.app.model.ExtractedVideo
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * Bilibili direct progressive MP4 extractor using public web-interface APIs.
 * Requests `fnval=1` (muxed MP4 with video+audio) so Android plays and saves directly without FFmpeg.
 */
object BilibiliExtractor {
    data class VideoInfo(val bvid: String, val cid: Long, val title: String, val cover: String?)

    fun parseViewPayload(payload: JSONObject): VideoInfo? {
        if (payload.optInt("code", -1) != 0) return null
        val data = payload.optJSONObject("data") ?: return null
        val bvid = data.optString("bvid").ifBlank { return null }
        val cid = data.optLong("cid", 0L).takeIf { it > 0L } ?: return null
        val title = data.optString("title").ifBlank { "B站视频 $bvid" }.trim()
        val cover = DouyinExtractor.normalizeUrl(data.optString("pic"))
        return VideoInfo(bvid = bvid, cid = cid, title = title, cover = cover)
    }

    fun parsePlayUrlPayload(payload: JSONObject): Pair<String, Long?>? {
        if (payload.optInt("code", -1) != 0) return null
        val data = payload.optJSONObject("data") ?: return null
        val durl = data.optJSONArray("durl") ?: return null
        if (durl.length() == 0) return null
        val first = durl.optJSONObject(0) ?: return null
        val url = DouyinExtractor.normalizeUrl(first.optString("url")) ?: return null
        val size = first.optLong("size", 0L).takeIf { it > 0L }
        return url to size
    }

    fun extract(url: String, client: OkHttpClient = HttpClients.default): ExtractResult? {
        if (!UrlUtils.isBilibili(url)) return null
        val bvid = UrlUtils.bilibiliBvidOf(url)
        val aid = UrlUtils.bilibiliAidOf(url)
        val query = when {
            bvid != null -> "bvid=$bvid"
            aid != null -> "aid=$aid"
            else -> return null
        }

        val cookies = HttpClients.webViewCookiesFor("https://www.bilibili.com/")
        val viewReq = Request.Builder()
            .url("https://api.bilibili.com/x/web-interface/view?$query")
            .header("User-Agent", HttpClients.DESKTOP_UA)
            .header("Referer", "https://www.bilibili.com/")
            .apply { if (cookies != null) header("Cookie", cookies) }
            .build()

        val info = try {
            client.newCall(viewReq).execute().use { resp ->
                if (!resp.isSuccessful) return null
                parseViewPayload(JSONObject(resp.body?.string().orEmpty()))
            }
        } catch (_: Exception) {
            null
        } ?: return null

        val playReq = Request.Builder()
            .url("https://api.bilibili.com/x/player/playurl?bvid=${info.bvid}&cid=${info.cid}&qn=80&fnval=1&platform=html5&high_quality=1")
            .header("User-Agent", HttpClients.DESKTOP_UA)
            .header("Referer", "https://www.bilibili.com/video/${info.bvid}/")
            .apply { if (cookies != null) header("Cookie", cookies) }
            .build()

        val (directUrl, size) = try {
            client.newCall(playReq).execute().use { resp ->
                if (!resp.isSuccessful) return null
                parsePlayUrlPayload(JSONObject(resp.body?.string().orEmpty()))
            }
        } catch (_: Exception) {
            null
        } ?: return null

        return ExtractResult.Success(
            ExtractedVideo(
                title = info.title.take(120),
                directUrl = directUrl,
                pageUrl = "https://www.bilibili.com/video/${info.bvid}/",
                platform = "B站",
                engine = "bilibili-api",
                thumbnailUrl = info.cover,
                filesizeBytes = size,
                headers = mapOf(
                    "User-Agent" to HttpClients.DESKTOP_UA,
                    "Referer" to "https://www.bilibili.com/video/${info.bvid}/",
                ),
            )
        )
    }
}
