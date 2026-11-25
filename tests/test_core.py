"""Unit and FastAPI endpoint tests for video-unwatermark."""

from __future__ import annotations

import asyncio
import json
import time
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from app.config import COOKIE_UPLOAD_DIR
from app.cookies import save_uploaded_cookies
from app.engines.sharepage import _media_urls, _parse_kuaishou_html
from app.engines.ytdlp import _pick_preview_format
from app.extract import extract_media
from app.jobs import JobStore
from app.main import app
from app.urls import aweme_id_of, is_douyin, is_kuaishou, photo_id_of, ytdlp_url
from app.util import (
    extract_url,
    hostname_of,
    is_blocked,
    is_hls,
    looks_like_login_error,
    sanitize_filename,
)


def test_extract_url_cn_slogans_and_encoded_queries():
    assert (
        extract_url("抖音公开视频：https://v.douyin.com/N-PiO4_whQo/复制此链接")
        == "https://v.douyin.com/N-PiO4_whQo"
    )
    assert (
        extract_url("看看这个https://www.bilibili.com/video/BV19uTtzvE2X/很棒！")
        == "https://www.bilibili.com/video/BV19uTtzvE2X"
    )
    # Must NOT corrupt percent-encoded query parameters
    signed = "https://example.com/watch?sig=a%2Fb%3Dc%26d%2Be&redirect=https%3A%2F%2Ffoo.bar"
    assert extract_url(signed) == signed
    # Fully percent-encoded URL should still decode
    assert extract_url("https%3A%2F%2Fyoutu.be%2Fabc123") == "https://youtu.be/abc123"
    # Uppercase scheme normalized
    assert extract_url("HTTPS://www.douyin.com/video/123") == "https://www.douyin.com/video/123"


def test_helpers_and_blocklist():
    assert hostname_of("https://www.douyin.com/video/1") == "douyin.com"
    assert is_blocked("https://www.netflix.com/title/1") == "Netflix"
    assert is_blocked("https://v.qq.com/x/cover/abc.html") == "腾讯视频"
    assert is_blocked("https://channels.weixin.qq.com/web/pages/feed") is not None
    assert is_blocked("https://www.douyin.com/video/1") is None
    assert sanitize_filename("a:b/c") == "a_b_c"
    assert is_hls("https://cdn.example/a.m3u8")
    assert is_douyin("https://www.iesdouyin.com/share/video/1")
    assert is_kuaishou("https://www.kuaishou.com/short-video/abc")
    assert aweme_id_of("https://www.douyin.com/video/987654") == "987654"
    assert photo_id_of("https://www.kuaishou.com/short-video/photoXYZ") == "photoXYZ"
    assert ytdlp_url("https://www.iesdouyin.com/share/video/987654") == "https://www.douyin.com/video/987654"
    # Kuaishou fallback when expanded URL lost photoId
    assert (
        ytdlp_url("https://www.kuaishou.com/short-video/3xkn2z3x3wnbhdw", "https://www.kuaishou.com/login")
        == "https://www.kuaishou.com/short-video/3xkn2z3x3wnbhdw"
    )


def test_looks_like_login_error():
    assert looks_like_login_error("HTTP Error 401: Unauthorized")
    assert looks_like_login_error("Sign in to confirm you're not a bot")
    assert looks_like_login_error("Fresh cookies are needed")
    assert not looks_like_login_error("HTTP Error 404: Not Found")


def test_kuaishou_init_state_parser_and_media_ranking():
    payload = {
        "obfuscatedKey": {
            "photo": {
                "caption": "《测试快手视频》",
                "mainMvUrls": [
                    {"cdn": "tymov2.a.kwimgs.com", "url": "https://tymov2.a.kwimgs.com/upic/test.mp4?di=123"}
                ],
                "coverUrls": [
                    {"cdn": "p2.a.yximgs.com", "url": "https://p2.a.yximgs.com/upic/cover.jpg"}
                ],
            }
        }
    }
    html = f"<script>window.INIT_STATE = {json.dumps(payload, ensure_ascii=False)};</script>"
    res = _parse_kuaishou_html("https://v.m.chenzhongtech.com/fw/photo/abc123", html)
    assert res is not None
    assert res["ok"] is True
    assert res["title"] == "《测试快手视频》"
    assert res["direct_url"] == "https://tymov2.a.kwimgs.com/upic/test.mp4?di=123"
    assert res["thumbnail"] == "https://p2.a.yximgs.com/upic/cover.jpg"

    # Encrypted remux placeholder should be ranked below direct kwimgs MP4
    raw_text = (
        '"https://v1-vod.kwaicdn.com/vod-rt-remux/hls-ts/v0/REALTIME_REMUX_TS_4999/enc_123_b.mp4" '
        '"https://tymov2.a.kwimgs.com/upic/2026/video_b.mp4?di=1"'
    )
    ranked = _media_urls(raw_text)
    assert ranked[0].startswith("https://tymov2.a.kwimgs.com/")


def test_pick_preview_format_prefers_progressive_mp4():
    formats = [
        {"url": "https://cdn/v.mp4", "ext": "mp4", "vcodec": "avc1", "acodec": "none", "height": 1080},
        {"url": "https://cdn/hls.m3u8", "ext": "mp4", "vcodec": "avc1", "acodec": "mp4a", "height": 720},
        {
            "url": "https://cdn/prog360.mp4",
            "ext": "mp4",
            "vcodec": "avc1",
            "acodec": "mp4a",
            "height": 360,
            "http_headers": {"Referer": "https://example.com/"},
        },
        {"url": "https://cdn/prog240.webm", "ext": "webm", "vcodec": "vp9", "acodec": "opus", "height": 480},
    ]
    url, hdrs = _pick_preview_format(formats)
    assert url == "https://cdn/prog360.mp4"
    assert hdrs == {"Referer": "https://example.com/"}


def test_extract_media_non_cn_needs_cookie_and_post_expand_block(monkeypatch):
    class FakeCookieEngine:
        name = "yt-dlp"
        stage = 20

        def available(self) -> bool:
            return True

        def extract(self, url: str) -> dict:
            return {"ok": False, "error": "needs cookie", "needs_cookie": True}

    monkeypatch.setattr("app.extract.get_engines", lambda: [FakeCookieEngine()])
    monkeypatch.setattr("app.extract.expand_share_url", lambda u: u)
    res = asyncio.run(extract_media("https://vimeo.com/1084537"))
    assert res["ok"] is False
    assert res.get("needs_cookie") is True
    assert res.get("hint")

    # Post-expand blocklist check: short URL redirecting to Netflix must be blocked
    monkeypatch.setattr("app.extract.expand_share_url", lambda _u: "https://www.netflix.com/title/80057281")
    blocked = asyncio.run(extract_media("https://t.cn/shortlink"))
    assert blocked["ok"] is False
    assert "Netflix" in blocked["error"]


def test_job_store_ttl_cleanup_removes_files_and_cookies(tmp_path: Path):
    store = JobStore(ttl=10.0, max_jobs=2)
    video_file = tmp_path / "sample.mp4"
    video_file.write_bytes(b"fake-mp4-data")
    cookie_path = save_uploaded_cookies(b"# Netscape HTTP Cookie File\n.example.com\tTRUE\t/\tFALSE\t0\tsid\tval\n")
    assert Path(cookie_path).is_file()

    job = store.create(
        page_url="https://example.com/v/1",
        title="t1",
        ext="mp4",
        filesize=13,
        thumbnail=None,
        extractor="yt-dlp",
        platform="example.com",
        direct_url=None,
        filepath=video_file,
        created_at=time.time() - 20.0,
        extra={"uploaded_cookiefile": cookie_path},
    )
    assert store.get(job.id) is None
    assert not video_file.exists()
    assert not Path(cookie_path).exists()


def test_fastapi_endpoints_and_cookie_cleanup_on_fail():
    with TestClient(app) as client:
        r = client.get("/api/health")
        assert r.status_code == 200
        body = r.json()
        assert body["ok"] is True
        assert isinstance(body["engines"], list)

        # Blocked platform rejected before extractors
        r2 = client.post("/api/parse", json={"url": "https://www.netflix.com/title/123"})
        assert r2.status_code == 200
        assert r2.json()["ok"] is False
        assert "Netflix" in r2.json()["error"]

        # Uploaded cookie file must be cleaned up when parse fails
        before = set(COOKIE_UPLOAD_DIR.glob("c_*.txt")) if COOKIE_UPLOAD_DIR.exists() else set()
        r3 = client.post(
            "/api/parse",
            data={"url": "https://www.netflix.com/title/123"},
            files={
                "cookies_file": (
                    "cookies.txt",
                    b"# Netscape HTTP Cookie File\n.example.com\tTRUE\t/\tFALSE\t0\tk\tv\n",
                    "text/plain",
                )
            },
        )
        assert r3.status_code == 200
        assert r3.json()["ok"] is False
        after = set(COOKIE_UPLOAD_DIR.glob("c_*.txt")) if COOKIE_UPLOAD_DIR.exists() else set()
        assert after == before
