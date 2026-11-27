package com.videoextractor.app.download

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.videoextractor.app.core.HttpClients
import com.videoextractor.app.core.UrlUtils
import com.videoextractor.app.model.DownloadState
import com.videoextractor.app.model.ExtractedVideo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Request
import okhttp3.Response
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext

/**
 * High-speed cancellable video downloader:
 * - Probes `Range: bytes=0-0` to detect total size and server Range support.
 * - Uses 3–6 parallel HTTP Range workers writing to a cached temp file via FileChannel
 *   (bypasses single-connection CDN throttling and MediaStore IPC overhead).
 * - Hooks every OkHttp Call into coroutine cancellation so canceling immediately aborts
 *   all blocking sockets and deletes partial temp / MediaStore files cleanly.
 * - Falls back cleanly to a 256KB buffered single-stream download when Range is unsupported.
 */
object MediaDownloader {
    private const val IO_BUF_SIZE = 128 * 1024
    private const val COPY_BUF_SIZE = 256 * 1024
    private const val MIN_PARALLEL_BYTES = 1_500_000L
    private const val TMP_PREFIX = "ve_dl_"
    private val CONTENT_RANGE_RE = Regex("""bytes\s+\d+-\d+/(\d+)""", RegexOption.IGNORE_CASE)

    data class ProbeInfo(val totalBytes: Long, val supportsRange: Boolean)

    fun chooseThreadCount(totalBytes: Long): Int = when {
        totalBytes >= 20L * 1024 * 1024 -> 6
        totalBytes >= 5L * 1024 * 1024 -> 4
        totalBytes >= MIN_PARALLEL_BYTES -> 3
        else -> 1
    }

    fun computeRanges(totalBytes: Long, threads: Int): List<LongRange> {
        if (totalBytes <= 0L) return emptyList()
        val n = threads.coerceIn(1, 8)
        if (n == 1) return listOf(0L until totalBytes)
        val chunkSize = (totalBytes + n - 1) / n
        val out = mutableListOf<LongRange>()
        var start = 0L
        while (start < totalBytes) {
            val end = minOf(start + chunkSize - 1, totalBytes - 1)
            out.add(start..end)
            start = end + 1
        }
        return out
    }

    fun parseTotalFromContentRange(header: String?): Long? {
        if (header.isNullOrBlank()) return null
        return CONTENT_RANGE_RE.find(header)?.groupValues?.getOrNull(1)?.toLongOrNull()
    }

    /** Removes any orphaned temp files left in cacheDir from interrupted/crashed downloads. */
    fun cleanupStaleTempFiles(context: Context) {
        try {
            context.cacheDir.listFiles()?.forEach { f ->
                if (f.isFile && f.name.startsWith(TMP_PREFIX)) {
                    f.delete()
                }
            }
        } catch (_: Throwable) {
            // ignore
        }
    }

    private fun baseRequestBuilder(video: ExtractedVideo, cookieHeader: String?): Request.Builder {
        val builder = Request.Builder().url(video.directUrl)
        var hasUa = false
        for ((k, v) in video.headers) {
            if (k.equals("User-Agent", ignoreCase = true)) hasUa = true
            builder.header(k, v)
        }
        if (!hasUa) {
            builder.header("User-Agent", HttpClients.MOBILE_UA)
        }
        if (!cookieHeader.isNullOrBlank()) {
            builder.header("Cookie", cookieHeader)
        }
        return builder
    }

    /** Executes an OkHttp request while binding cancellation directly to `call.cancel()`. */
    private suspend inline fun <T> executeCancellable(req: Request, block: (Response) -> T): T {
        coroutineContext.ensureActive()
        val call: Call = HttpClients.download.newCall(req)
        val handle = coroutineContext[Job]?.invokeOnCompletion { cause ->
            if (cause != null) {
                call.cancel()
            }
        }
        return try {
            call.execute().use { resp ->
                coroutineContext.ensureActive()
                block(resp)
            }
        } finally {
            handle?.dispose()
        }
    }

    private suspend fun probe(video: ExtractedVideo, cookieHeader: String?): ProbeInfo {
        return try {
            val req = baseRequestBuilder(video, cookieHeader)
                .header("Range", "bytes=0-0")
                .get()
                .build()
            executeCancellable(req) { resp ->
                when (resp.code) {
                    206 -> {
                        val total = parseTotalFromContentRange(resp.header("Content-Range")) ?: -1L
                        ProbeInfo(totalBytes = total, supportsRange = total > 0L)
                    }
                    200 -> {
                        val total = resp.header("Content-Length")?.toLongOrNull() ?: -1L
                        val acceptRange = resp.header("Accept-Ranges").orEmpty().contains("bytes", ignoreCase = true)
                        ProbeInfo(totalBytes = total, supportsRange = acceptRange && total > 0L)
                    }
                    else -> ProbeInfo(totalBytes = -1L, supportsRange = false)
                }
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Exception) {
            ProbeInfo(totalBytes = -1L, supportsRange = false)
        }
    }

    suspend fun downloadToGallery(
        context: Context,
        video: ExtractedVideo,
        onProgress: (DownloadState.Downloading) -> Unit,
    ): DownloadState = withContext(Dispatchers.IO) {
        if (video.isHls) {
            return@withContext DownloadState.Failed("当前直链为 HLS (.m3u8) 流媒体，暂不支持直接保存单文件，请点击「复制直链」使用播放器打开")
        }

        cleanupStaleTempFiles(context)

        val ext = video.ext.lowercase().ifBlank { "mp4" }
        val baseName = UrlUtils.sanitizeFilename(video.title)
        val displayName = "${baseName}_${System.currentTimeMillis() % 10000}.$ext"
        val mimeType = if (ext == "webm") "video/webm" else "video/mp4"
        val cookieHeader = HttpClients.webViewCookiesFor(video.directUrl)

        onProgress(DownloadState.Downloading(-1f, 0L, -1L, 0L, 1, "正在连接并探测多线程加速…"))

        val probeInfo = probe(video, cookieHeader)
        coroutineContext.ensureActive()
        val threads = if (probeInfo.supportsRange) chooseThreadCount(probeInfo.totalBytes) else 1

        if (threads > 1 && probeInfo.totalBytes >= MIN_PARALLEL_BYTES) {
            val tmpFile = File(context.cacheDir, "${TMP_PREFIX}${System.currentTimeMillis()}.$ext.tmp")
            try {
                downloadParallelToFile(
                    video = video,
                    cookieHeader = cookieHeader,
                    totalBytes = probeInfo.totalBytes,
                    threads = threads,
                    destFile = tmpFile,
                    onProgress = onProgress,
                )
                coroutineContext.ensureActive()
                onProgress(
                    DownloadState.Downloading(
                        progress = 1f,
                        bytesRead = probeInfo.totalBytes,
                        totalBytes = probeInfo.totalBytes,
                        speedBytesPerSec = 0L,
                        threads = threads,
                        stage = "正在写入系统相册…",
                    )
                )
                return@withContext saveTempFileToGallery(context, tmpFile, displayName, mimeType)
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Exception) {
                coroutineContext.ensureActive()
                // Fall back to single-stream download if parallel range fails on a strict CDN
            } finally {
                tmpFile.delete()
            }
        }

        downloadSingleStreamToGallery(context, video, cookieHeader, displayName, mimeType, onProgress)
    }

    private suspend fun downloadParallelToFile(
        video: ExtractedVideo,
        cookieHeader: String?,
        totalBytes: Long,
        threads: Int,
        destFile: File,
        onProgress: (DownloadState.Downloading) -> Unit,
    ) = coroutineScope {
        val ranges = computeRanges(totalBytes, threads)
        val downloaded = AtomicLong(0L)
        val lastReportTime = AtomicLong(System.currentTimeMillis())
        val lastReportBytes = AtomicLong(0L)
        val reportLock = Any()

        RandomAccessFile(destFile, "rw").use { raf ->
            raf.setLength(totalBytes)
            val channel = raf.channel

            val jobs = ranges.map { range ->
                async(Dispatchers.IO) {
                    var offset = range.first
                    val end = range.last
                    var attempt = 0
                    while (offset <= end) {
                        coroutineContext.ensureActive()
                        try {
                            val req = baseRequestBuilder(video, cookieHeader)
                                .header("Range", "bytes=$offset-$end")
                                .get()
                                .build()
                            executeCancellable(req) { resp ->
                                if (resp.code != 206 && !(offset == 0L && resp.code == 200)) {
                                    throw IllegalStateException("HTTP ${resp.code} on range $offset-$end")
                                }
                                val body = resp.body ?: throw IllegalStateException("Empty chunk body")
                                body.byteStream().use { input ->
                                    val buf = ByteArray(IO_BUF_SIZE)
                                    while (offset <= end) {
                                        coroutineContext.ensureActive()
                                        val remaining = (end - offset + 1).coerceAtMost(buf.size.toLong()).toInt()
                                        val n = input.read(buf, 0, remaining)
                                        if (n <= 0) break
                                        val bb = ByteBuffer.wrap(buf, 0, n)
                                        var pos = offset
                                        while (bb.hasRemaining()) {
                                            val written = channel.write(bb, pos)
                                            pos += written
                                        }
                                        offset += n
                                        val totalRead = downloaded.addAndGet(n.toLong())
                                        val now = System.currentTimeMillis()
                                        if (now - lastReportTime.get() >= 250L || totalRead >= totalBytes) {
                                            synchronized(reportLock) {
                                                val prevTime = lastReportTime.get()
                                                val dt = now - prevTime
                                                if (dt >= 250L || totalRead >= totalBytes) {
                                                    val prevBytes = lastReportBytes.get()
                                                    val speed = if (dt > 0) ((totalRead - prevBytes) * 1000L) / dt else 0L
                                                    lastReportTime.set(now)
                                                    lastReportBytes.set(totalRead)
                                                    onProgress(
                                                        DownloadState.Downloading(
                                                            progress = (totalRead.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f),
                                                            bytesRead = totalRead,
                                                            totalBytes = totalBytes,
                                                            speedBytesPerSec = speed.coerceAtLeast(0L),
                                                            threads = threads,
                                                            stage = "${threads}线程并发下载中",
                                                        )
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            if (offset <= end) {
                                throw IllegalStateException("Premature EOF at $offset/$end")
                            }
                        } catch (ce: CancellationException) {
                            throw ce
                        } catch (e: Exception) {
                            coroutineContext.ensureActive()
                            attempt++
                            if (attempt > 2) throw e
                        }
                    }
                }
            }
            jobs.awaitAll()
        }
    }

    private suspend fun downloadSingleStreamToGallery(
        context: Context,
        video: ExtractedVideo,
        cookieHeader: String?,
        displayName: String,
        mimeType: String,
        onProgress: (DownloadState.Downloading) -> Unit,
    ): DownloadState = withContext(Dispatchers.IO) {
        val req = baseRequestBuilder(video, cookieHeader).get().build()
        var target: OutputTarget? = null
        var completed = false
        try {
            executeCancellable(req) { resp ->
                if (!resp.isSuccessful) {
                    return@executeCancellable DownloadState.Failed("下载失败（HTTP ${resp.code}）")
                }
                val body = resp.body ?: return@executeCancellable DownloadState.Failed("响应体为空")
                val total = body.contentLength()
                val outTarget = openOutputStream(context, displayName, mimeType)
                    ?: return@executeCancellable DownloadState.Failed("无法创建系统相册视频文件")
                target = outTarget

                BufferedOutputStream(outTarget.stream, COPY_BUF_SIZE).use { os ->
                    body.byteStream().use { input ->
                        val buf = ByteArray(IO_BUF_SIZE)
                        var readTotal = 0L
                        var lastTime = System.currentTimeMillis()
                        var lastBytes = 0L
                        while (true) {
                            coroutineContext.ensureActive()
                            val n = input.read(buf)
                            if (n <= 0) break
                            os.write(buf, 0, n)
                            readTotal += n
                            val now = System.currentTimeMillis()
                            val dt = now - lastTime
                            if (dt >= 250L || (total > 0 && readTotal >= total)) {
                                val speed = if (dt > 0) ((readTotal - lastBytes) * 1000L) / dt else 0L
                                lastTime = now
                                lastBytes = readTotal
                                val progress = if (total > 0) (readTotal.toFloat() / total.toFloat()).coerceIn(0f, 1f) else -1f
                                onProgress(
                                    DownloadState.Downloading(
                                        progress = progress,
                                        bytesRead = readTotal,
                                        totalBytes = total,
                                        speedBytesPerSec = speed.coerceAtLeast(0L),
                                        threads = 1,
                                        stage = "单线程下载中",
                                    )
                                )
                            }
                        }
                        os.flush()
                    }
                }
                coroutineContext.ensureActive()
                markMediaStoreComplete(context, outTarget.uri)
                completed = true
                DownloadState.Saved(displayName = displayName, uriString = outTarget.uri.toString())
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            DownloadState.Failed("网络下载失败：${e.message ?: "未知错误"}")
        } finally {
            if (!completed) {
                target?.cleanup()
            }
        }
    }

    private suspend fun saveTempFileToGallery(
        context: Context,
        srcFile: File,
        displayName: String,
        mimeType: String,
    ): DownloadState {
        val target = openOutputStream(context, displayName, mimeType)
            ?: return DownloadState.Failed("无法创建系统相册视频文件")
        var completed = false
        return try {
            BufferedOutputStream(target.stream, COPY_BUF_SIZE).use { os ->
                FileInputStream(srcFile).use { input ->
                    val buf = ByteArray(COPY_BUF_SIZE)
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buf)
                        if (n <= 0) break
                        os.write(buf, 0, n)
                    }
                    os.flush()
                }
            }
            markMediaStoreComplete(context, target.uri)
            completed = true
            DownloadState.Saved(displayName = displayName, uriString = target.uri.toString())
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            DownloadState.Failed("写入相册失败：${e.message ?: "未知错误"}")
        } finally {
            if (!completed) {
                target.cleanup()
            }
        }
    }

    private fun markMediaStoreComplete(context: Context, uri: Uri) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val doneValues = ContentValues().apply {
                put(MediaStore.Video.Media.IS_PENDING, 0)
            }
            context.contentResolver.update(uri, doneValues, null, null)
        }
    }

    private data class OutputTarget(
        val stream: OutputStream,
        val uri: Uri,
        val cleanup: () -> Unit,
    )

    private fun openOutputStream(context: Context, displayName: String, mimeType: String): OutputTarget? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Video.Media.MIME_TYPE, mimeType)
                put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/VideoExtractor")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return null
            val stream = resolver.openOutputStream(uri) ?: run {
                resolver.delete(uri, null, null)
                return null
            }
            OutputTarget(stream, uri) {
                runCatching { stream.close() }
                runCatching { resolver.delete(uri, null, null) }
            }
        } else {
            @Suppress("DEPRECATION")
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                "VideoExtractor",
            )
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, displayName)
            val stream = FileOutputStream(file)
            OutputTarget(stream, Uri.fromFile(file)) {
                runCatching { stream.close() }
                runCatching { file.delete() }
            }
        }
    }
}
