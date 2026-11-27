package com.videoextractor.app.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.videoextractor.app.core.HttpClients
import com.videoextractor.app.model.DownloadState
import com.videoextractor.app.model.ExtractResult
import com.videoextractor.app.model.ExtractedVideo

@kotlin.OptIn(ExperimentalLayoutApi::class)
@Composable
fun MainScreen(viewModel: ExtractorViewModel) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    val notifPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { _ ->
        // Start download regardless; Foreground Service still runs even if notification is denied.
        viewModel.downloadCurrent()
    }

    fun requestDownloadWithNotifPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }
        viewModel.downloadCurrent()
    }

    LaunchedEffect(state.snackbarMessage) {
        val msg = state.snackbarMessage
        if (!msg.isNullOrBlank()) {
            snackbarHostState.showSnackbar(msg)
            viewModel.clearSnackbar()
        }
    }

    state.interactiveBrowserUrl?.let { browserUrl ->
        InteractiveWebViewDialog(
            initialUrl = browserUrl,
            onCaptured = { viewModel.onInteractiveVideoCaptured(it) },
            onDismiss = { reExtract -> viewModel.closeInteractiveBrowser(reExtract) },
        )
    }

    Scaffold(
        containerColor = BgDark,
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Spacer(modifier = Modifier.height(14.dp))

            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = SurfaceVariantDark,
                ) {
                    Text(
                        text = "PUBLIC UGC ONLY · 本地多线程直提",
                        color = AccentSky,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
                TextButton(onClick = { viewModel.clearLocalCookies() }) {
                    Text("清除登录态", color = TextMuted, fontSize = 12.sp)
                }
            }

            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "公开视频提取",
                color = TextPrimary,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "纯手机本地多线程加速下载，支持后台/锁屏下载与系统「分享」直达。",
                color = TextMuted,
                fontSize = 13.sp,
                lineHeight = 19.sp,
            )

            Spacer(modifier = Modifier.height(10.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                listOf(
                    "抖音", "快手", "闲鱼", "小红书", "B站",
                    "YouTube", "TikTok", "X", "Instagram", "Facebook", "Reddit", "Vimeo", "网页嗅探",
                ).forEach { tag ->
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = SurfaceDark,
                    ) {
                        Text(
                            text = tag,
                            color = TextMuted,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Input card
            Card(
                colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    OutlinedTextField(
                        value = state.inputText,
                        onValueChange = viewModel::onInputChanged,
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                        maxLines = 5,
                        placeholder = {
                            Text(
                                text = "粘贴分享口令或视频链接…\n支持抖音/快手/闲鱼/小红书/B站/YouTube/TikTok/X/Instagram/Facebook/Reddit/Vimeo",
                                color = TextMuted.copy(alpha = 0.7f),
                                fontSize = 13.sp,
                            )
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AccentSky,
                            unfocusedBorderColor = SurfaceVariantDark,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary,
                            cursorColor = AccentSky,
                        ),
                        shape = RoundedCornerShape(12.dp),
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            onClick = {
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val clip = cm.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
                                if (!clip.isNullOrBlank()) {
                                    viewModel.onInputChanged(clip)
                                } else {
                                    viewModel.showToast("剪贴板为空")
                                }
                            },
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Icon(Icons.Default.ContentPaste, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("粘贴")
                        }

                        OutlinedButton(
                            onClick = { viewModel.clearInput() },
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Icon(Icons.Default.DeleteOutline, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("清空")
                        }

                        Button(
                            onClick = { viewModel.startExtract() },
                            enabled = !state.isParsing,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = AccentSky),
                        ) {
                            if (state.isParsing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    color = BgDark,
                                    strokeWidth = 2.dp,
                                )
                            } else {
                                Text("提取视频", color = BgDark, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    if (state.isParsing && state.stageMessage.isNotBlank()) {
                        Spacer(modifier = Modifier.height(12.dp))
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(4.dp)),
                            color = AccentSky,
                            trackColor = SurfaceVariantDark,
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = state.stageMessage,
                            color = AccentSky,
                            fontSize = 12.sp,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Result area
            when (val res = state.result) {
                is ExtractResult.Success -> {
                    ResultSuccessCard(
                        video = res.video,
                        elapsedSeconds = state.elapsedSeconds,
                        previewEnabled = state.previewEnabled,
                        downloadState = state.downloadState,
                        onEnablePreview = { viewModel.enablePreview() },
                        onDownload = { requestDownloadWithNotifPermission() },
                        onCancelDownload = { viewModel.cancelDownload() },
                        onCopyDirect = {
                            copyToClipboard(context, res.video.directUrl)
                            viewModel.showToast("已复制视频直链")
                        },
                        onOpenSaved = { uriStr ->
                            runCatching {
                                val intent = Intent(Intent.ACTION_VIEW).apply {
                                    setDataAndType(Uri.parse(uriStr), "video/*")
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(intent)
                            }.onFailure {
                                viewModel.showToast("已保存至相册 Movies/VideoExtractor")
                            }
                        },
                    )
                }

                is ExtractResult.NeedsAuth -> {
                    StatusCard(
                        title = "${res.platform} 需要登录或风控验证",
                        message = res.message,
                        accentColor = WarningAmber,
                        actionLabel = "在内置浏览器打开",
                        onAction = { viewModel.openInteractiveBrowser(res.pageUrl) },
                    )
                }

                is ExtractResult.NoVideo -> {
                    StatusCard(
                        title = "未发现视频内容",
                        message = res.message,
                        accentColor = WarningAmber,
                    )
                }

                is ExtractResult.Blocked -> {
                    StatusCard(
                        title = "不支持该平台",
                        message = res.message,
                        accentColor = ErrorCoral,
                    )
                }

                is ExtractResult.Failure -> {
                    StatusCard(
                        title = "解析未成功",
                        message = res.message,
                        accentColor = ErrorCoral,
                        actionLabel = if (!res.pageUrl.isNullOrBlank()) "在内置浏览器打开嗅探" else null,
                        onAction = res.pageUrl?.let { target -> { viewModel.openInteractiveBrowser(target) } },
                    )
                }

                null -> Unit
            }

            Spacer(modifier = Modifier.height(28.dp))
        }
    }
}

@Composable
private fun ResultSuccessCard(
    video: ExtractedVideo,
    elapsedSeconds: Double?,
    previewEnabled: Boolean,
    downloadState: DownloadState,
    onEnablePreview: () -> Unit,
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onCopyDirect: () -> Unit,
    onOpenSaved: (String) -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Surface(shape = RoundedCornerShape(8.dp), color = SurfaceVariantDark) {
                    Text(
                        text = video.platform,
                        color = AccentSky,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
                Surface(shape = RoundedCornerShape(8.dp), color = SurfaceVariantDark) {
                    Text(
                        text = video.engine,
                        color = SuccessGreen,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
                if (elapsedSeconds != null) {
                    Text(
                        text = "%.1fs".format(elapsedSeconds),
                        color = TextMuted,
                        fontSize = 11.sp,
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = video.title,
                color = TextPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            val sizeLabel = video.filesizeBytes?.let { formatBytes(it) } ?: "大小未知"
            Text(
                text = "${video.ext.uppercase()} · $sizeLabel",
                color = TextMuted,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 2.dp),
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Load ExoPlayer strictly on-demand so preview doesn't compete with download bandwidth / CDN limits.
            if (previewEnabled) {
                VideoPreviewPlayer(
                    video = video,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .clip(RoundedCornerShape(12.dp)),
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF090E1A)),
                    contentAlignment = Alignment.Center,
                ) {
                    if (!video.thumbnailUrl.isNullOrBlank()) {
                        AsyncImage(
                            model = video.thumbnailUrl,
                            contentDescription = video.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.42f)),
                        )
                    }
                    Button(
                        onClick = onEnablePreview,
                        shape = RoundedCornerShape(999.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = SurfaceDark.copy(alpha = 0.9f)),
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, tint = AccentSky, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("点击在线预览", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            val isDownloading = downloadState is DownloadState.Downloading
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (isDownloading) {
                    OutlinedButton(
                        onClick = onCancelDownload,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = ErrorCoral),
                    ) {
                        Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("取消下载", fontWeight = FontWeight.Bold)
                    }
                } else {
                    Button(
                        onClick = onDownload,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = SuccessGreen),
                    ) {
                        Icon(Icons.Default.Download, contentDescription = null, tint = BgDark, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("多线程保存到相册", color = BgDark, fontWeight = FontWeight.Bold)
                    }
                }

                OutlinedButton(
                    onClick = onCopyDirect,
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("复制直链")
                }
            }

            when (downloadState) {
                is DownloadState.Downloading -> {
                    Spacer(modifier = Modifier.height(12.dp))
                    if (downloadState.progress >= 0f) {
                        LinearProgressIndicator(
                            progress = { downloadState.progress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(4.dp)),
                            color = SuccessGreen,
                            trackColor = SurfaceVariantDark,
                        )
                    } else {
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(4.dp)),
                            color = SuccessGreen,
                            trackColor = SurfaceVariantDark,
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    val pctText = if (downloadState.progress >= 0f) "${(downloadState.progress * 100).toInt()}%" else ""
                    val speedText = if (downloadState.speedBytesPerSec > 0L) " · ${formatSpeed(downloadState.speedBytesPerSec)}" else ""
                    val sizeText = if (downloadState.totalBytes > 0L) {
                        "${formatBytes(downloadState.bytesRead)} / ${formatBytes(downloadState.totalBytes)}"
                    } else {
                        "已下载 ${formatBytes(downloadState.bytesRead)}"
                    }
                    Text(
                        text = "${downloadState.stage} $pctText · $sizeText$speedText",
                        color = AccentSky,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = "已开启前台保活服务：切到后台或锁屏仍会继续下载，通知栏可随时查看或取消",
                        color = TextMuted,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(top = 3.dp),
                    )
                }

                is DownloadState.Saved -> {
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(SurfaceVariantDark)
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("已保存到相册 (Movies/VideoExtractor)", color = SuccessGreen, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            Text(downloadState.displayName, color = TextMuted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        TextButton(onClick = { onOpenSaved(downloadState.uriString) }) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, tint = AccentSky, modifier = Modifier.size(16.dp))
                            Text("打开", color = AccentSky)
                        }
                    }
                }

                is DownloadState.Failed -> {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(text = downloadState.error, color = ErrorCoral, fontSize = 12.sp)
                }

                DownloadState.Idle -> Unit
            }
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun VideoPreviewPlayer(
    video: ExtractedVideo,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val player = remember(video.directUrl) {
        val headers = video.headers.toMutableMap()
        HttpClients.webViewCookiesFor(video.directUrl)?.let { headers["Cookie"] = it }
        val dataSourceFactory = OkHttpDataSource.Factory(HttpClients.download)
            .setDefaultRequestProperties(headers)
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .build()
            .apply {
                setMediaItem(MediaItem.fromUri(video.directUrl))
                prepare()
                playWhenReady = true
            }
    }

    DisposableEffect(player) {
        onDispose {
            player.release()
        }
    }

    Box(modifier = modifier.background(Color.Black)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player = player
                    useController = true
                }
            },
            update = { view ->
                view.player = player
            },
        )
    }
}

@Composable
private fun StatusCard(
    title: String,
    message: String,
    accentColor: Color,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(text = title, color = accentColor, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(6.dp))
            Text(text = message, color = TextPrimary, fontSize = 13.sp, lineHeight = 19.sp)
            if (actionLabel != null && onAction != null) {
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = onAction,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = AccentSky),
                ) {
                    Icon(Icons.Default.Language, contentDescription = null, tint = BgDark, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(actionLabel, color = BgDark, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("video_url", text))
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    if (bytes < 1024 * 1024) return "%.1f KB".format(bytes / 1024.0)
    return "%.1f MB".format(bytes / 1024.0 / 1024.0)
}

private fun formatSpeed(bps: Long): String {
    if (bps < 1024) return "$bps B/s"
    if (bps < 1024 * 1024) return "%.0f KB/s".format(bps / 1024.0)
    return "%.1f MB/s".format(bps / 1024.0 / 1024.0)
}
