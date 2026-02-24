package com.unhuman.outlookalerter.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class LogManagerTest {

    private LogManager logManager;

    @BeforeEach
    void setup() {
        logManager = LogManager.getInstance();
        logManager.clearLogs();
    }

    // ───────── Singleton ─────────

    @Nested
    @DisplayName("singleton")
    class Singleton {

        @Test
        @DisplayName("getInstance returns same instance")
        void sameInstance() {
            assertSame(LogManager.getInstance(), LogManager.getInstance());
        }

        @Test
        @DisplayName("is never null")
        void notNull() {
            assertNotNull(LogManager.getInstance());
        }
    }

    // ───────── Logging Methods ─────────

    @Nested
    @DisplayName("logging methods")
    class LoggingMethods {

        @Test
        @DisplayName("info() logs at INFO level")
        void infoLevel() {
            logManager.info("test info message");
            String logs = logManager.getLogsAsString();
            assertTrue(logs.contains("[INFO]"));
            assertTrue(logs.contains("test info message"));
        }

        @Test
        @DisplayName("info() with category logs at INFO level with category")
        void infoCategoryLevel() {
            logManager.info(LogCategory.DATA_FETCH, "fetch message");
            String logs = logManager.getLogsAsString();
            assertTrue(logs.contains("[INFO]"));
            assertTrue(logs.contains("[Data Fetch]"));
            assertTrue(logs.contains("fetch message"));
        }

        @Test
        @DisplayName("warn() logs at WARN level")
        void warnLevel() {
            logManager.warn("test warning");
            String logs = logManager.getLogsAsString();
            assertTrue(logs.contains("[WARN]"));
            assertTrue(logs.contains("test warning"));
        }

        @Test
        @DisplayName("warn() with category")
        void warnCategory() {
            logManager.warn(LogCategory.ALERT_PROCESSING, "alert warning");
            String logs = logManager.getLogsAsString();
            assertTrue(logs.contains("[WARN]"));
            assertTrue(logs.contains("[Alert Processing]"));
            assertTrue(logs.contains("alert warning"));
        }

        @Test
        @DisplayName("error() logs at ERROR level")
        void errorLevel() {
            logManager.error("test error");
            String logs = logManager.getLogsAsString();
            assertTrue(logs.contains("[ERROR]"));
            assertTrue(logs.contains("test error"));
        }

        @Test
        @DisplayName("error() with category")
        void errorCategory() {
            logManager.error(LogCategory.GENERAL, "general error");
            String logs = logManager.getLogsAsString();
            assertTrue(logs.contains("[ERROR]"));
            assertTrue(logs.contains("[General]"));
        }

        @Test
        @DisplayName("error() with exception includes stack trace")
        void errorWithException() {
            Exception ex = new RuntimeException("boom");
            logManager.error("something failed", ex);
            String logs = logManager.getLogsAsString();
            assertTrue(logs.contains("[ERROR]"));
            assertTrue(logs.contains("something failed"));
            assertTrue(logs.contains("boom"));
            assertTrue(logs.contains("RuntimeException"));
        }

        @Test
        @DisplayName("error() with category and exception")
        void errorCategoryWithException() {
            Exception ex = new IllegalArgumentException("bad arg");
            logManager.error(LogCategory.DATA_FETCH, "parse failed", ex);
            String logs = logManager.getLogsAsString();
            assertTrue(logs.contains("[ERROR]"));
            assertTrue(logs.contains("[Data Fetch]"));
            assertTrue(logs.contains("bad arg"));
        }
    }

    // ───────── Log Formatting ─────────

    @Nested
    @DisplayName("log formatting")
    class Formatting {

        @Test
        @DisplayName("log entries contain timestamp")
        void hasTimestamp() {
            logManager.info("timestamped");
            String logs = logManager.getLogsAsString();
            // Timestamp format: yyyy-MM-dd HH:mm:ss.SSS
            assertTrue(logs.matches("(?s).*\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3}.*"));
        }

        @Test
        @DisplayName("default category is GENERAL")
        void defaultCategory() {
            logManager.info("default cat");
            String logs = logManager.getLogsAsString();
            assertTrue(logs.contains("[General]"));
        }
    }

    // ───────── Buffer Management ─────────

    @Nested
    @DisplayName("buffer management")
    class BufferManagement {

        @Test
        @DisplayName("clearLogs empties the buffer")
        void clearLogs() {
            logManager.info("before clear");
            assertTrue(logManager.getLogsAsString().contains("before clear"));

            logManager.clearLogs();
            assertEquals("", logManager.getLogsAsString());
        }

        @Test
        @DisplayName("logs are ordered chronologically")
        void chronologicalOrder() {
            logManager.info("first");
            logManager.info("second");
            logManager.info("third");

            String logs = logManager.getLogsAsString();
            int firstIdx = logs.indexOf("first");
            int secondIdx = logs.indexOf("second");
            int thirdIdx = logs.indexOf("third");

            assertTrue(firstIdx < secondIdx);
            assertTrue(secondIdx < thirdIdx);
        }

        @Test
        @DisplayName("getLogsAsString returns all logged messages")
        void allMessages() {
            logManager.info("msg1");
            logManager.warn("msg2");
            logManager.error("msg3");

            String logs = logManager.getLogsAsString();
            assertTrue(logs.contains("msg1"));
            assertTrue(logs.contains("msg2"));
            assertTrue(logs.contains("msg3"));
        }
    }

    // ───────── Category Filtering ─────────

    @Nested
    @DisplayName("category filtering")
    class Filtering {

        @BeforeEach
        void resetFilters() {
            // Ensure all category filters are enabled and text filter is clear
            for (LogCategory cat : LogCategory.values()) {
                logManager.setFilterEnabled(cat, true);
            }
            logManager.setTextFilter("");
        }

        @Test
        @DisplayName("all filters enabled by default")
        void allEnabledByDefault() {
            for (LogCategory cat : LogCategory.values()) {
                assertTrue(logManager.isFilterEnabled(cat),
                    cat + " should be enabled by default");
            }
        }

        @Test
        @DisplayName("disabling filter hides category from output")
        void disableFilter() {
            logManager.info(LogCategory.MEETING_INFO, "meeting note");
            assertTrue(logManager.getLogsAsString().contains("meeting note"));

            logManager.setFilterEnabled(LogCategory.MEETING_INFO, false);
            // getLogsAsString respects filters
            assertFalse(logManager.getLogsAsString().contains("meeting note"));

            // Re-enable
            logManager.setFilterEnabled(LogCategory.MEETING_INFO, true);
            assertTrue(logManager.getLogsAsString().contains("meeting note"));
        }

        @Test
        @DisplayName("getCategories returns all enum values")
        void allCategories() {
            LogCategory[] cats = LogManager.getCategories();
            assertEquals(4, cats.length);
            assertTrue(Arrays.asList(cats).contains(LogCategory.DATA_FETCH));
            assertTrue(Arrays.asList(cats).contains(LogCategory.MEETING_INFO));
            assertTrue(Arrays.asList(cats).contains(LogCategory.ALERT_PROCESSING));
            assertTrue(Arrays.asList(cats).contains(LogCategory.GENERAL));
        }
    }

    // ───────── Text Filtering ─────────

    @Nested
    @DisplayName("text filtering")
    class TextFiltering {

        @BeforeEach
        void resetFilters() {
            for (LogCategory cat : LogCategory.values()) {
                logManager.setFilterEnabled(cat, true);
            }
            logManager.setTextFilter("");
        }

        @Test
        @DisplayName("empty text filter shows all logs")
        void emptyFilterShowsAll() {
            logManager.info("alpha message");
            logManager.info("beta message");
            logManager.setTextFilter("");
            String logs = logManager.getLogsAsString();
            assertTrue(logs.contains("alpha message"));
            assertTrue(logs.contains("beta message"));
        }

        @Test
        @DisplayName("text filter hides non-matching entries")
        void filterHidesNonMatching() {
            logManager.info("token refreshed successfully");
            logManager.info("calendar fetch started");
            logManager.setTextFilter("token");
            String logs = logManager.getLogsAsString();
            assertTrue(logs.contains("token refreshed"));
            assertFalse(logs.contains("calendar fetch"));
        }

        @Test
        @DisplayName("text filter is case-insensitive")
        void caseInsensitive() {
            logManager.info("Token Refreshed Successfully");
            logManager.setTextFilter("token refreshed");
            assertTrue(logManager.getLogsAsString().contains("Token Refreshed"));
        }

        @Test
        @DisplayName("clearing text filter restores all logs")
        void clearingFilterRestoresAll() {
            logManager.info("first entry");
            logManager.info("second entry");
            logManager.setTextFilter("first");
            assertFalse(logManager.getLogsAsString().contains("second entry"));

            logManager.setTextFilter("");
            assertTrue(logManager.getLogsAsString().contains("first entry"));
            assertTrue(logManager.getLogsAsString().contains("second entry"));
        }

        @Test
        @DisplayName("null text filter treated as empty")
        void nullFilterTreatedAsEmpty() {
            logManager.info("some message");
            logManager.setTextFilter(null);
            assertTrue(logManager.getLogsAsString().contains("some message"));
        }

        @Test
        @DisplayName("text filter works with category filter")
        void textAndCategoryFilterCombine() {
            logManager.info(LogCategory.DATA_FETCH, "fetch token data");
            logManager.info(LogCategory.GENERAL, "general token info");
            logManager.info(LogCategory.DATA_FETCH, "fetch calendar data");

            logManager.setTextFilter("token");
            logManager.setFilterEnabled(LogCategory.GENERAL, false);

            String logs = logManager.getLogsAsString();
            assertTrue(logs.contains("fetch token data"));
            assertFalse(logs.contains("general token info"));   // category disabled
            assertFalse(logs.contains("fetch calendar data"));  // text doesn't match
        }

        @Test
        @DisplayName("getTextFilter returns current filter")
        void getTextFilter() {
            logManager.setTextFilter("hello");
            assertEquals("hello", logManager.getTextFilter());
        }
    }

    // ───────── LogCategory enum ─────────

    @Nested
    @DisplayName("LogCategory enum")
    class LogCategoryTest {

        @Test
        @DisplayName("has correct display names")
        void displayNames() {
            assertEquals("Data Fetch", LogCategory.DATA_FETCH.getDisplayName());
            assertEquals("Meeting Info", LogCategory.MEETING_INFO.getDisplayName());
            assertEquals("Alert Processing", LogCategory.ALERT_PROCESSING.getDisplayName());
            assertEquals("General", LogCategory.GENERAL.getDisplayName());
        }

        @Test
        @DisplayName("toString returns display name")
        void toStringReturnsDisplayName() {
            assertEquals("Data Fetch", LogCategory.DATA_FETCH.toString());
            assertEquals("General", LogCategory.GENERAL.toString());
        }
    }

    // ───────── LogEntry ─────────

    @Nested
    @DisplayName("LogEntry")
    class LogEntryTest {

        @Test
        @DisplayName("formats message correctly")
        void formattedMessage() {
            LogEntry entry = new LogEntry("2026-02-19 10:30:00.123", "INFO", "hello world", LogCategory.GENERAL);
            assertEquals(
                "2026-02-19 10:30:00.123 [INFO] [General] hello world",
                entry.getFormattedMessage()
            );
        }

        @Test
        @DisplayName("preserves all fields")
        void fields() {
            LogEntry entry = new LogEntry("ts", "WARN", "msg", LogCategory.DATA_FETCH);
            assertEquals("ts", entry.getTimestamp());
            assertEquals("WARN", entry.getLevel());
            assertEquals("msg", entry.getMessage());
            assertEquals(LogCategory.DATA_FETCH, entry.getCategory());
        }
    }
}
