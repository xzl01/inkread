package com.inkread.reader.core.parse

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** feed 嗅探（判断地址本身是否就是 RSS/Atom）测试。 */
class FeedSnifferTest {

    @Test
    fun `按 content type 判断`() {
        assertTrue(FeedSniffer.looksLikeFeed("application/rss+xml; charset=utf-8", ""))
        assertTrue(FeedSniffer.looksLikeFeed("application/atom+xml", ""))
        assertTrue(FeedSniffer.looksLikeFeed("text/xml", ""))
        assertFalse(FeedSniffer.looksLikeFeed("text/html", "<html></html>"))
        assertFalse(FeedSniffer.looksLikeFeed(null, "<html></html>"))
    }

    @Test
    fun `按正文根元素判断`() {
        assertTrue(FeedSniffer.looksLikeFeed(null, "<?xml version=\"1.0\"?>\n<rss version=\"2.0\">"))
        assertTrue(FeedSniffer.looksLikeFeed(null, "<feed xmlns=\"http://www.w3.org/2005/Atom\">"))
        assertTrue(FeedSniffer.looksLikeFeed(null, "<rdf:RDF xmlns:rdf=\"x\">"))
        assertFalse(FeedSniffer.looksLikeFeed(null, "{\"version\":\"https://jsonfeed.org/version/1\"}"))
        assertFalse(FeedSniffer.looksLikeFeed(null, null))
    }
}
