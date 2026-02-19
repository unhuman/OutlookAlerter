package com.unhuman.outlookalerter.util;

/**
 * Utility class for HTML-related operations.
 */
public class HtmlUtil {
    /**
     * Escape a string for safe inclusion in HTML content.
     * Prevents HTML injection from untrusted calendar event data.
     */
    public static String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                   .replace("<", "&lt;")
                   .replace(">", "&gt;")
                   .replace("\"", "&quot;")
                   .replace("'", "&#39;");
    }
}
