# video-extractor Android

纯手机本地运行的 Android 视频提取应用（Kotlin + Jetpack Compose + OkHttp + 内置 WebView 嗅探 + Media3 ExoPlayer），**不依赖外部服务器**。

## 特性

- **纯本地直接提取**：使用手机自身网络 IP，避免云服务器机房 IP 触发平台风控
- **多平台支持**：
  - **抖音**：`iesdouyin.com` 分享页 `_ROUTER_DATA` 解析（自动去 `playwm`）+ WebView 兜底嗅探
  - **快手**：分享页状态解析与高清 MP4 排序 + WebView 兜底嗅探
  - **闲鱼**：支持 `m.tb.cn` 短链 JS 重定向还原、无登录优先 H5 游客 MTOP 接口（`mtop.taobao.idle.awesome.detail`），在内置浏览器登录后自动升级 PC MTOP + UD/HD 视频接口（`mtop.idle.cloud.video.query`）
  - **小红书**：`__INITIAL_STATE__` / `originVideoKey` 解析 + WebView 兜底嗅探
  - **B站**：公开 Web API 直取音画合一渐进式 MP4（无需 FFmpeg）
  - **YouTube**：InnerTube 公开接口（`ANDROID_VR` / `IOS` / `ANDROID`）直取音画合一渐进式 MP4 / HLS
  - **TikTok**：`__UNIVERSAL_DATA_FOR_REHYDRATION__` / `SIGI_STATE` 解析 + WebView 嗅探
  - **X (Twitter)**：官方公开 Syndication JSON + FxTwitter 兜底提取最高码率 MP4
  - **Instagram / Facebook**：公开嵌入页 / 原生高清流提取 + 本地 WebView 登录态嗅探
  - **Reddit / Vimeo**：官方公开帖子 JSON / `player.vimeo.com` 配置接口直取 MP4 / HLS
  - **通用网页**：OpenGraph / HTML5 `<video>` / JSON-LD / 内置浏览器实时嗅探
- **系统分享直达**：在抖音、闲鱼、快手、小红书、B站中点击「分享」发送到本应用，自动开始提取
- **内置浏览器（本地 Cookie）**：遇到风控或登录限制时，可在本机 WebView 内自行登录/验证并实时嗅探视频，Cookie 仅保存在本机 `CookieManager`，可随时一键清除
- **应用内预览 & 直存相册**：内置 ExoPlayer 预览（自动携带 `Referer` / `User-Agent` / 本地 Cookie），一键下载保存到系统相册 `Movies/VideoExtractor`
- **仅公开 UGC**：同样内置 VIP / DRM 与微信视频号拒绝列表，不破解会员、不绕过验证码

## 本地构建

需要 JDK 17+ 与 Android SDK（`compileSdk 35`）：

```bash
cd android
./gradlew testDebugUnitTest
./gradlew assembleRelease
```

生成的 APK 位于：

```text
android/app/build/outputs/apk/release/app-release.apk
```

> 未配置签名环境变量时，Release 构建默认使用调试签名，方便直接安装测试；如需正式签名，设置环境变量 `ANDROID_KEYSTORE_PATH`、`ANDROID_KEYSTORE_PASSWORD`、`ANDROID_KEY_ALIAS`、`ANDROID_KEY_PASSWORD`。
