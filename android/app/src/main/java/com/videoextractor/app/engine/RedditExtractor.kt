package com.videoextractor.app.engine

import com.videoextractor.app.core.HttpClients
import com.videoextractor.app.core.UrlUtils
import com.videoextractor.app.model.ExtractResult
import com.videoextractor.app.model.ExtractedVideo
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * Reddit public video extractor via Reddit's public post `.json` endpoint.
 */
object RedditExtractor {

    fun parseListingJson(rawJson: String, pageUrl: String): ExtractResult? {
        val postData = try {
            val trimmed = rawJson.trim()
            if (trimmed.startsWith("[")) {
                JSONArray(trimmed)
                    .optJSONObject(0)
                    ?.optJSONObject("data")
                    ?.optJSONArray("children")
                    ?.optJSONObject(0)
                    ?.optJSONObject("data")
            } else {
                JSONObject(trimmed)
                    .optJSONObject("data")
                    ?.optJSONArray("children")
                    ?.optJSONObject(0)
                    ?.optJSONObject("data")
            }
        } catch (_: Exception) {
            null
        } ?: return null

        val title = postData.optString("title").trim().ifBlank { "Reddit 视频" }.take(120)
        val thumb = DouyinExtractor.normalizeUrl(postData.optString("thumbnail"))

        // Check post itself and crosspost parent
        val candidates = buildList {
            add(postData)
            val crossposts = postData.optJSONArray("crosspost_parent_list")
            if (crossposts != null && crossposts.length() > 0) {
                crossposts.optJSONObject(0)?.let { add(it) }
            }
        }

        for (post in candidates) {
            val media = post.optJSONObject("secure_media") ?: post.optJSONObject("media")
            val redditVideo = media?.optJSONObject("reddit_video")
                ?: post.optJSONObject("preview")?.optJSONObject("reddit_video_preview")
                ?: continue

            val fallbackMp4 = DouyinExtractor.normalizeUrl(redditVideo.optString("fallback_url"))
            val hlsUrl = DouyinExtractor.normalizeUrl(redditVideo.optString("hls_url"))
            val hasAudio = redditVideo.optBoolean("has_audio", false)

            // Prefer direct MP4 when no separate audio track is needed (for direct MediaStore download),
            // otherwise fallback MP4 is still directly downloadable (or HLS if fallback missing).
            val chosen = fallbackMp4 ?: hlsUrl ?: continue
            val ext = if (chosen.lowercase().contains(".m3u8")) "m3u8" else "mp4"

            return ExtractResult.Success(
                ExtractedVideo(
                    title = if (hasAudio && ext == "mp4") title else title,
                    directUrl = chosen,
                    pageUrl = pageUrl,
                    platform = "Reddit",
                    engine = "reddit-json",
                    ext = ext,
                    thumbnailUrl = thumb,
                    headers = mapOf("User-Agent" to HttpClients.DESKTOP_UA),
                )
            )
        }
        return null
    }

    fun extract(url: String, client: OkHttpClient = HttpClients.default): ExtractResult? {
        if (!UrlUtils.isReddit(url)) return null
        val cleanBase = url.substringBefore('?').trimEnd('/')
        val jsonUrl = if (cleanBase.endsWith(".json")) cleanBase else "$cleanBase.json"
        val req = Request.Builder()
            .url(jsonUrl)
            .header("User-Agent", "android:com.videoextractor.app:v1.0.0")
            .header("Accept", "application/json")
            .build()

        return try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                parseListingJson(resp.body?.string().orEmpty(), url)
            }
        } catch (_: Exception) {
            null
        }
    }
}
