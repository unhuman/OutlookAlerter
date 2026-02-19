package com.unhuman.outlookalerter.util

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested

import static org.junit.jupiter.api.Assertions.*

class HtmlUtilTest {

    @Nested
    @DisplayName("escapeHtml()")
    class EscapeHtml {

        @Test
        @DisplayName("escapes ampersand")
        void escapesAmpersand() {
            assertEquals("Tom &amp; Jerry", HtmlUtil.escapeHtml("Tom & Jerry"))
        }

        @Test
        @DisplayName("escapes less-than")
        void escapesLessThan() {
            assertEquals("a &lt; b", HtmlUtil.escapeHtml("a < b"))
        }

        @Test
        @DisplayName("escapes greater-than")
        void escapesGreaterThan() {
            assertEquals("a &gt; b", HtmlUtil.escapeHtml("a > b"))
        }

        @Test
        @DisplayName("escapes double quotes")
        void escapesDoubleQuotes() {
            assertEquals("say &quot;hello&quot;", HtmlUtil.escapeHtml('say "hello"'))
        }

        @Test
        @DisplayName("escapes single quotes")
        void escapesSingleQuotes() {
            assertEquals("it&#39;s fine", HtmlUtil.escapeHtml("it's fine"))
        }

        @Test
        @DisplayName("returns empty string for null input")
        void nullInput() {
            assertEquals("", HtmlUtil.escapeHtml(null))
        }

        @Test
        @DisplayName("returns empty string for empty input")
        void emptyInput() {
            assertEquals("", HtmlUtil.escapeHtml(""))
        }

        @Test
        @DisplayName("passes through plain text unchanged")
        void plainText() {
            assertEquals("Hello World", HtmlUtil.escapeHtml("Hello World"))
        }

        @Test
        @DisplayName("handles multiple special characters together")
        void multipleSpecialChars() {
            String input = '<script>alert("XSS & it\'s bad")</script>'
            String expected = '&lt;script&gt;alert(&quot;XSS &amp; it&#39;s bad&quot;)&lt;/script&gt;'
            assertEquals(expected, HtmlUtil.escapeHtml(input))
        }

        @Test
        @DisplayName("handles HTML entities that are already escaped (double-escaping)")
        void alreadyEscaped() {
            // If input already has &amp; it should become &amp;amp;
            assertEquals("&amp;amp;", HtmlUtil.escapeHtml("&amp;"))
        }

        @Test
        @DisplayName("handles unicode characters unchanged")
        void unicodeCharacters() {
            assertEquals("日本語 café ñ", HtmlUtil.escapeHtml("日本語 café ñ"))
        }
    }
}
