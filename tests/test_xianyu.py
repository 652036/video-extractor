"""Unit and endpoint tests for Xianyu (闲鱼 / goofish.com) video extraction."""

from __future__ import annotations

from pathlib import Path

from fastapi.testclient import TestClient

from app.cookies import CookieOpts, cookies_for_domains
from app.engines.xianyu import XianyuEngine, parse_video_query, parse_xianyu_detail
from app.main import app
from app.urls import (
    expand_share_url,
    extract_tb_js_redirect,
    is_xianyu,
    xianyu_item_id_of,
)
from app.util import extract_url, platform_of


def test_extract_xianyu_share_text_and_js_redirect():
    slogan = (
        "【闲鱼】https://m.tb.cn/h.jKTfz9p?tk=D3k36h8HJSG CZ057 "
        "「快来捡漏【逆战NZ功能齐全，适合PVP用，专业冲分，数据稳，包更新。】」点击链接直接打开"
    )
    url = extract_url(slogan)
    assert url == "https://m.tb.cn/h.jKTfz9p?tk=D3k36h8HJSG"

    html = (
        "<script>var shortName = 'h.jKTfz9p'; "
        "var url = 'https://h5.m.goofish.com/item?forceFlush=1&itemId=1101224115545&hitNativeDetail=true';</script>"
    )
    redirected = extract_tb_js_redirect(html)
    assert redirected == "https://h5.m.goofish.com/item?forceFlush=1&itemId=1101224115545&hitNativeDetail=true"
    assert is_xianyu(redirected)
    assert xianyu_item_id_of(redirected) == "1101224115545"
    assert platform_of(redirected) == "闲鱼"


def test_xianyu_host_boundaries_and_item_id():
    assert is_xianyu("https://www.goofish.com/item?id=1101224115545")
    assert is_xianyu("https://h5.m.goofish.com/item?itemId=1101224115545")
    assert is_xianyu("https://www.goofish.com./item?id=1101224115545")
    assert is_xianyu("https://2.taobao.com/item.htm?id=1101224115545")
    assert not is_xianyu("https://notgoofish.com/item?id=1101224115545")
    assert not is_xianyu("https://goofish.com.example.invalid/item?id=1101224115545")
    assert not is_xianyu("https://www.douyin.com/video/1101224115545")

    assert xianyu_item_id_of("https://www.goofish.com/item?id=1101224115545") == "1101224115545"
    assert xianyu_item_id_of("https://h5.m.goofish.com/item?forceFlush=1&itemId=99887766") == "99887766"
    assert xianyu_item_id_of("https://www.goofish.com/item/12345678") == "12345678"


def test_expand_share_url_normalizes_xianyu(monkeypatch):
    monkeypatch.setattr(
        "app.urls._follow_tb_short",
        lambda _u: "https://h5.m.goofish.com/item?forceFlush=1&itemId=1101224115545&needNotPreGet=true",
    )
    resolved = expand_share_url("https://m.tb.cn/h.jKTfz9p?tk=D3k36h8HJSG")
    assert resolved == "https://www.goofish.com/item?id=1101224115545"


def test_parse_xianyu_detail_video_and_hd_query():
    detail_payload = {
        "api": "mtop.taobao.idle.pc.detail",
        "ret": ["SUCCESS::调用成功"],
        "data": {
            "itemDO": {
                "itemId": "1101224115545",
                "title": "闲鱼实拍视频测试商品",
                "videoPlayInfo": {
                    "url": "//img.alicdn.com/bao/uploaded/cover.jpg",
                    "playUrl": "https://cloud.video.taobao.com/play/u/123/p/1/e/6/t/1/sd_video.mp4",
                    "mtsVid": "456789012345",
                },
                "imageInfos": [{"url": "http://img.alicdn.com/bao/uploaded/p1.jpg"}],
            }
        },
    }
    parsed = parse_xianyu_detail(detail_payload, "https://www.goofish.com/item?id=1101224115545")
    assert parsed is not None
    assert parsed["ok"] is True
    assert parsed["title"] == "闲鱼实拍视频测试商品"
    assert parsed["platform"] == "闲鱼"
    assert parsed["thumbnail"] == "https://img.alicdn.com/bao/uploaded/cover.jpg"
    assert parsed["direct_url"] == "https://cloud.video.taobao.com/play/u/123/p/1/e/6/t/1/sd_video.mp4"
    assert parsed["_mts_vid"] == "456789012345"

    vq_payload = {
        "api": "mtop.idle.cloud.video.query",
        "ret": ["SUCCESS::调用成功"],
        "data": {
            "cdnVideo": {
                "resources": [
                    {"video_url": "https://cloud.video.taobao.com/play/u/123/p/1/e/6/t/1/hd_video.mp4"}
                ]
            }
        },
    }
    assert parse_video_query(vq_payload) == "https://cloud.video.taobao.com/play/u/123/p/1/e/6/t/1/hd_video.mp4"

    # H5 guest share API uses camelCase `itemDo` and `sd480pUrl` / `ld320pUrl`
    h5_payload = {
        "api": "mtop.taobao.idle.awesome.detail",
        "ret": ["SUCCESS::调用成功"],
        "data": {
            "itemDo": {
                "itemId": "1101224115545",
                "desc": "闲鱼H5分享页视频",
                "videoPlayInfo": {
                    "url": "https://img.alicdn.com/h5cover.jpg",
                    "sd480pUrl": "https://cloud.video.taobao.com/play/u/1/p/1/e/6/t/1/sd480.mp4",
                    "ld320pUrl": "https://cloud.video.taobao.com/play/u/1/p/1/e/6/t/1/ld320.mp4",
                },
            }
        },
    }
    h5_parsed = parse_xianyu_detail(h5_payload, "https://h5.m.goofish.com/item?id=1101224115545")
    assert h5_parsed is not None
    assert h5_parsed["ok"] is True
    assert h5_parsed["title"] == "闲鱼H5分享页视频"
    assert h5_parsed["direct_url"].endswith("/sd480.mp4")


def test_parse_xianyu_detail_image_only_and_risk_block():
    image_only = {
        "ret": ["SUCCESS::调用成功"],
        "data": {
            "itemDO": {
                "itemId": "1101224115545",
                "title": "纯图文商品",
                "imageInfos": [{"url": "https://img.alicdn.com/a.jpg"}],
            }
        },
    }
    res = parse_xianyu_detail(image_only, "https://www.goofish.com/item?id=1101224115545")
    assert res is not None
    assert res["ok"] is False
    assert res.get("no_video") is True
    assert not res.get("needs_cookie")

    risk_blocked = {
        "api": "mtop.taobao.idle.pc.detail",
        "ret": ["RGV587_ERROR::SM::哎哟喂,被挤爆啦,请稍后重试!"],
        "data": {"url": "https://passport.goofish.com/mini_login.htm"},
    }
    res2 = parse_xianyu_detail(risk_blocked, "https://www.goofish.com/item?id=1101224115545")
    assert res2 is not None
    assert res2["ok"] is False
    assert res2.get("needs_cookie") is True


def test_cookies_for_domains_filters_netscape(tmp_path: Path):
    cookie_file = tmp_path / "cookies.txt"
    cookie_file.write_text(
        "# Netscape HTTP Cookie File\n"
        ".goofish.com\tTRUE\t/\tFALSE\t0\t_m_h5_tk\tabc_123\n"
        "#HttpOnly_.taobao.com\tTRUE\t/\tTRUE\t0\tcookie2\tsecval\n"
        ".other.com\tTRUE\t/\tFALSE\t0\tignore\t1\n",
        encoding="utf-8",
    )
    loaded = cookies_for_domains(("goofish.com", "taobao.com"), CookieOpts(cookiefile=str(cookie_file)))
    names = {c["name"]: c["value"] for c in loaded}
    assert names == {"_m_h5_tk": "abc_123", "cookie2": "secval"}


def test_xianyu_end_to_end_parse_endpoint(monkeypatch):
    monkeypatch.setattr(
        "app.urls._follow_tb_short",
        lambda _u: "https://h5.m.goofish.com/item?itemId=1101224115545",
    )
    monkeypatch.setattr(
        "app.engines.xianyu._extract_via_mtop",
        lambda item_id, page_url, _cookies: {
            "ok": True,
            "title": "逆战NZ演示视频",
            "ext": "mp4",
            "filesize": None,
            "thumbnail": "https://img.alicdn.com/cover.jpg",
            "extractor": "xianyu",
            "extractor_key": "Xianyu",
            "platform": "闲鱼",
            "direct_url": "https://cloud.video.taobao.com/play/u/1/p/1/e/6/t/1/demo.mp4",
            "needs_merge": False,
            "page_url": page_url,
            "http_headers": {"Referer": "https://www.goofish.com/"},
        },
    )
    # Reset cached engines so XianyuEngine is definitely included
    monkeypatch.setattr("app.extract._ENGINES", [XianyuEngine()])
    with TestClient(app) as client:
        r = client.post(
            "/api/parse",
            json={
                "url": "【闲鱼】https://m.tb.cn/h.jKTfz9p?tk=D3k36h8HJSG CZ057 「快来捡漏」点击链接直接打开"
            },
        )
        assert r.status_code == 200
        body = r.json()
        assert body["ok"] is True
        assert body["platform"] == "闲鱼"
        assert body["extractor"] == "xianyu"
        assert body["direct_url"].endswith("/demo.mp4")
        assert body["thumbnail"] == "https://img.alicdn.com/cover.jpg"
