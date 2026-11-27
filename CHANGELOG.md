# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html) where tagged.

## [Unreleased]

### Fixed

- Preserve percent-encoded path and query data while extracting share URLs, including signed parameters and encoded punctuation.
- Match Douyin and Kuaishou hosts only at domain-label boundaries, avoiding accidental routing of unrelated lookalike domains.
- Normalize a trailing DNS root dot consistently for platform identification and the existing blocked-host checks.

### Added

- Xianyu (闲鱼 / `goofish.com` / `2.taobao.com` / `m.tb.cn`) video extraction engine (`app/engines/xianyu.py`) supporting `m.tb.cn` JS redirect expansion, H5 guest MTOP API (`mtop.taobao.idle.awesome.detail`), PC MTOP + UD/HD video query (`mtop.idle.cloud.video.query`) with scoped local cookies, and headless Chrome passive capture fallback.
- Scoped cookie helper (`cookies_for_domains`) supporting Netscape `cookies.txt` and `--cookies-from-browser`.
- Offline unit and endpoint tests (`tests/test_core.py`, `tests/test_url_integrity.py`, `tests/test_xianyu.py`), executed by CI without real video requests or cookies.

## [1.2.0] - 2026-08-22

### Added

- Cross-platform lux bootstrap: automatically selects the correct v0.24.1 release asset for Linux, macOS (Darwin), and Windows (x86_64 and arm64). Windows uses `lux.exe` with zip extraction.
- Windows startup scripts (`start.ps1` / `start.bat`) with virtual environment setup, optional engines, Playwright Chromium, ffmpeg installation tips (winget/choco), and lux ensure.
- Improved `start.sh` for macOS: Homebrew ffmpeg install attempt, Chrome.app detection, and lux management via `app.engines.lux.ensure_lux` (single source of truth).

## [1.1.0] - 2026-08

### Added

- Multi-engine public UGC parser (share-page, douyin-browser, yt-dlp, videofetch, you-get, webparser, lux)
- FastAPI UI + JSON API on port 8787
- Optional local Netscape / browser cookies for authentication
- Explicit VIP/DRM host block list
