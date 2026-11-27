# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html) where tagged.

## [1.0.0]

### Added

- Multi-engine public UGC video extractor (`share-page`, `douyin-browser`, `xianyu`, `yt-dlp`, `videofetch`, `you-get`, `webparser`, `lux`) with FastAPI Web UI and JSON API on port 8787.
- Xianyu (闲鱼 / `goofish.com` / `2.taobao.com` / `m.tb.cn`) extractor supporting `m.tb.cn` JS redirect expansion, H5 guest MTOP API (`mtop.taobao.idle.awesome.detail`), PC MTOP + UD/HD video query (`mtop.idle.cloud.video.query`) with scoped local cookies, and headless Chrome passive capture fallback.
- Share URL integrity handling: preserves percent-encoded paths and signed query parameters, enforces strict domain-label host boundaries, and normalizes trailing DNS root dots.
- Optional local Netscape `cookies.txt` and `--cookies-from-browser` support (`VIDEO_EXTRACTOR_COOKIES` / `VIDEO_EXTRACTOR_COOKIES_FROM_BROWSER`).
- Explicit VIP / DRM host block list and refusal of WeChat Channels login/MITM capture.
- Cross-platform startup scripts (`start.sh`, `start.ps1`, `start.bat`) with automatic `lux` binary bootstrap.
- Offline unit, URL integrity, and FastAPI endpoint test suites (`tests/test_core.py`, `tests/test_url_integrity.py`, `tests/test_xianyu.py`) executed in CI.
