package com.videoextractor.app

import com.videoextractor.app.core.UrlUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlUtilsTest {

    @Test
    fun extractUrlFromChineseShareSlogansAndPreserveSignedQuery() {
        val xianyu = "【闲鱼】https://m.tb.cn/h.jKTfz9p?tk=D3k36h8HJSG CZ057 「快来捡漏」点击链接直接打开"
        assertEquals("https://m.tb.cn/h.jKTfz9p?tk=D3k36h8HJSG", UrlUtils.extractUrl(xianyu))

        val signed = "看看这个 https://cdn.example.com/v/a%2Fb.mp4?sig=abc%2B123%3D&exp=999！"
        assertEquals("https://cdn.example.com/v/a%2Fb.mp4?sig=abc%2B123%3D&exp=999", UrlUtils.extractUrl(signed))
    }

    @Test
    fun strictHostBoundariesAndDnsRootDot() {
        assertTrue(UrlUtils.isDouyin("https://www.douyin.com./video/7321000000000000001"))
        assertFalse(UrlUtils.isDouyin("https://notdouyin.com/video/7321000000000000001"))

        assertTrue(UrlUtils.isXianyu("https://h5.m.goofish.com/item?id=1101224115545"))
        assertTrue(UrlUtils.isXianyu("https://www.goofish.com./item?id=1101224115545"))
        assertFalse(UrlUtils.isXianyu("https://fakegoofish.com/item?id=1101224115545"))
        assertEquals("1101224115545", UrlUtils.xianyuItemIdOf("https://www.goofish.com/item?id=1101224115545"))
    }

    @Test
    fun blocklistEnforcedWithRootDot() {
        assertNotNull(UrlUtils.checkBlocked("https://v.qq.com./x/cover/abc.html"))
        assertNotNull(UrlUtils.checkBlocked("https://www.iqiyi.com/v_123.html"))
        assertNotNull(UrlUtils.checkBlocked("https://channels.weixin.qq.com/web/pages/feed"))
        assertNull(UrlUtils.checkBlocked("https://www.bilibili.com/video/BV1xx411c7mD"))
    }

    @Test
    fun extractTbJsRedirectParsesVarUrl() {
        val html = """<script>var url = 'https://www.goofish.com/item?id=1101224115545&ut_sk=1';</script>"""
        assertEquals(
            "https://www.goofish.com/item?id=1101224115545&ut_sk=1",
            UrlUtils.extractTbJsRedirect(html),
        )
    }

    @Test
    fun bilibiliAndXhsIdExtraction() {
        assertEquals("BV1xx411c7mD", UrlUtils.bilibiliBvidOf("https://www.bilibili.com/video/BV1xx411c7mD/?p=1"))
        assertEquals("66a1b2c3000000001e01abcd", UrlUtils.xhsNoteIdOf("https://www.xiaohongshu.com/explore/66a1b2c3000000001e01abcd"))
    }

    @Test
    fun overseasPlatformIdsAndBoundaries() {
        assertEquals("dQw4w9WgXcQ", UrlUtils.youtubeVideoIdOf("https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=10s"))
        assertEquals("dQw4w9WgXcQ", UrlUtils.youtubeVideoIdOf("https://youtu.be/dQw4w9WgXcQ?si=abc"))
        assertEquals("dQw4w9WgXcQ", UrlUtils.youtubeVideoIdOf("https://youtube.com/shorts/dQw4w9WgXcQ"))
        assertFalse(UrlUtils.isYouTube("https://notyoutube.com/watch?v=dQw4w9WgXcQ"))

        assertEquals("1850000000000000001", UrlUtils.tweetIdOf("https://x.com/user/status/1850000000000000001?s=20"))
        assertEquals("C9AbCdEf123", UrlUtils.instagramShortcodeOf("https://www.instagram.com/reel/C9AbCdEf123/"))
        assertEquals("76979871", UrlUtils.vimeoIdOf("https://vimeo.com/76979871"))
        assertTrue(UrlUtils.isTikTok("https://www.tiktok.com/@test/video/7321000000000000001"))
        assertTrue(UrlUtils.isFacebook("https://fb.watch/abc123/"))
        assertTrue(UrlUtils.isReddit("https://www.reddit.com/r/videos/comments/abc123/demo/"))
    }
}
