package com.unhuman.outlookalerter.util

import groovy.transform.CompileStatic

/**
 * Utility class for HTML-related operations.
 */
@CompileStatic
class HtmlUtil {
    /**
     * Escape a string for safe inclusion in HTML content.
     * Prevents HTML injection from untrusted calendar event data.
     */
    static String escapeHtml(String text) {
        if (text == null) return ""
        return text.replace("&", "&amp;")
                   .replace("<", "&lt;")
                   .replace(">", "&gt;")
                   .replace("\"", "&quot;")
                   .replace("'", "&#39;")
    }
}
