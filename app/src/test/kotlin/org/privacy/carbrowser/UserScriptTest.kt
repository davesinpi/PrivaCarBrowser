package org.privacy.carbrowser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UserScriptTest {
    @Test fun matching() {
        val p = "*://*.example.com/*"
        assertTrue(matchPattern(p, "https://example.com/"))
        assertTrue(matchPattern(p, "http://a.b.example.com/x?y"))
        assertTrue(matchPattern(p, "https://example.com:8443/x"))
        assertFalse(matchPattern(p, "https://evilexample.com/"))
        assertFalse(matchPattern(p, "https://example.com.evil.com/"))
        assertFalse(matchPattern(p, "https://a.com/?u=https://b.example.com/")) // no injection via query
        assertFalse(matchPattern(p, "file:///sdcard/example.com/"))
        assertTrue(matchPattern("https://news.site/article/*", "https://news.site/article/1"))
        assertFalse(matchPattern("https://news.site/article/*", "http://news.site/article/1"))
        assertTrue(matchPattern("*", "https://anything/"))
        assertFalse(matchPattern("*", "about:blank"))
    }

    @Test fun parsing() {
        val s = UserScript.parse(
            """
            // ==UserScript==
            // @name   Dark mode
            // @match  *://*.example.com/*
            // @match  https://other.org/*
            // @run-at document-start
            // ==/UserScript==
            document.documentElement.style.filter = 'invert(1)';
            """.trimIndent()
        )
        assertEquals("Dark mode", s.name)
        assertEquals(listOf("*://*.example.com/*", "https://other.org/*"), s.matches)
        assertTrue(s.runAtStart)

        val bare = UserScript.parse("alert(1)")
        assertEquals("Untitled script", bare.name)
        assertEquals(listOf("*"), bare.matches)
        assertFalse(bare.runAtStart)
    }
}
