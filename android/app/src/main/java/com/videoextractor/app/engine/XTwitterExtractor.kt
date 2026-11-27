package com.videoextractor.app.engine

import com.videoextractor.app.core.HttpClients
import com.videoextractor.app.core.UrlUtils
import com.videoextractor.app.model.ExtractResult
import com.videoextractor.app.model.ExtractedVideo
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import kotlin.math.PI

/**
 * X / Twitter public video extractor using official X syndication JSON + FxTwitter fallback.
 * Picks the highest-bitrate MP4 stream without requiring login for public posts.
 */
object XTwitterExtractor {

    fun syndicationToken(tweetId: String): String {
        val num = tweetId.toDoubleOrNull() ?: return "0"
        return ((num / 1e15) * PI).toString()
            .replace("0", "")
            .replace(".", "")
            .ifBlank { "1" }
    }

    fun parseSyndicationPayload(payload: JSONObject, pageUrl: String): ExtractResult? {
        val text = payload.optString("text").trim().ifBlank { "X 视频" }.lines().first().take(120)
        val mediaDetails = payload.optJSONArray("mediaDetails") ?: return null
        var bestUrl: String? = null
        var bestBitrate = -1L
        var cover: String? = null

        for (i in 0 until mediaDetails.length()) {
            val media = mediaDetails.optJSONObject(i) ?: continue
            if (cover == null) cover = DouyinExtractor.normalizeUrl(media.optString("media_url_https"))
            val variants = media.optJSONObject("video_info")?.optJSONArray("variants") ?: continue
            for (j in 0 until variants.length()) {
                val v = variants.optJSONObject(j) ?: continue
                val contentType = v.optString("content_type").lowercase()
                if (!contentType.contains("mp4")) continue
                val u = DouyinExtractor.normalizeUrl(v.optString("url")) ?: continue
                val br = v.optLong("bitrate", 0L)
                if (bestUrl == null || br > bestBitrate) {
                    bestUrl = u
                    bestBitrate = br
                }
            }
        }
        if (bestUrl == null) return null

        return ExtractResult.Success(
            ExtractedVideo(
                title = text,
                directUrl = bestUrl,
                pageUrl = pageUrl,
                platform = "X",
                engine = "x-syndication",
                thumbnailUrl = cover,
                headers = mapOf(
                    "User-Agent" to HttpClients.DESKTOP_UA,
                    "Referer" to "https://x.com/",
                ),
            )
        )
    }

    fun parseFxTwitterPayload(payload: JSONObject, pageUrl: String): ExtractResult? {
        val tweet = payload.optJSONObject("tweet") ?: return null
        val text = tweet.optString("text").trim().ifBlank { "X 视频" }.lines().first().take(120)
        val videos = tweet.optJSONObject("media")?.optJSONArray("videos") ?: return null
        if (videos.length() == 0) return null
        val first = videos.optJSONObject(0) ?: return null
        val direct = DouyinExtractor.normalizeUrl(first.optString("url")) ?: return null
        val cover = DouyinExtractor.normalizeUrl(first.optString("thumbnail_url"))
        return ExtractResult.Success(
            ExtractedVideo(
                title = text,
                directUrl = direct,
                pageUrl = pageUrl,
                platform = "X",
                engine = "x-fxapi",
                thumbnailUrl = cover,
                headers = mapOf("User-Agent" to HttpClients.DESKTOP_UA),
            )
        )
    }

    fun extract(url: String, client: OkHttpClient = HttpClients.default): ExtractResult? {
        val tweetId = UrlUtils.tweetIdOf(url) ?: return null
        val pageUrl = "https://x.com/i/status/$tweetId"

        // 1. Official public syndication endpoint
        val token = syndicationToken(tweetId)
        val synReq = Request.Builder()
            .url("https://cdn.syndication.twimg.com/tweet-result?id=$tweetId&lang=en&token=$token")
            .header("User-Agent", HttpClients.DESKTOP_UA)
            .header("Accept", "application/json")
            .build()
        try {
            client.newCall(synReq).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string().orEmpty()
                    if (body.startsWith("{")) {
                        parseSyndicationPayload(JSONObject(body), pageUrl)?.let { return it }
                    }
                }
            }
        } catch (_: Exception) {
            // fallback below
        }

        // 2. Public FxTwitter API fallback
        val fxReq = Request.Builder()
            .url("https://api.fxtwitter.com/status/$tweetId")
            .header("User-Agent", HttpClients.DESKTOP_UA)
            .header("Accept", "application/json")
            .build()
        return try {
            client.newCall(fxReq).execute().use { resp ->
                if (!resp.isSuccessful) return null
                parseFxTwitterPayload(JSONObject(resp.body?.string().orEmpty()), pageUrl)
            }
        } catch (_: Exception) {
            null
        }
    }
}
