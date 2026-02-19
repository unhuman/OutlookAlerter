package com.unhuman.outlookalerter.util;

import com.unhuman.outlookalerter.model.CalendarEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ScreenFlasherFactoryTest {

    @Nested
    @DisplayName("createScreenFlasher()")
    class CreateScreenFlasher {

        @Test
        @DisplayName("returns a non-null ScreenFlasher")
        void returnsNonNull() {
            ScreenFlasher flasher = ScreenFlasherFactory.createScreenFlasher();
            assertNotNull(flasher);
        }

        @Test
        @DisplayName("returns a ScreenFlasher implementation")
        void implementsInterface() {
            ScreenFlasher flasher = ScreenFlasherFactory.createScreenFlasher();
            assertTrue(flasher instanceof ScreenFlasher);
        }

        @Test
        @DisplayName("returns Mac flasher on macOS")
        void macFlasher() {
            String os = System.getProperty("os.name").toLowerCase();
            ScreenFlasher flasher = ScreenFlasherFactory.createScreenFlasher();

            if (os.contains("mac")) {
                assertTrue(flasher instanceof MacScreenFlasher,
                    "On macOS, should return MacScreenFlasher but got " + flasher.getClass().getSimpleName());
            }
        }

        @Test
        @DisplayName("returns consistent type on repeated calls")
        void consistentType() {
            ScreenFlasher first = ScreenFlasherFactory.createScreenFlasher();
            ScreenFlasher second = ScreenFlasherFactory.createScreenFlasher();
            assertEquals(first.getClass(), second.getClass());
        }
    }

    // ───────── ScreenFlasher interface ─────────

    @Nested
    @DisplayName("ScreenFlasher interface")
    class InterfaceContract {

        @Test
        @DisplayName("forceCleanup default is no-op")
        void defaultForceCleanup() {
            // Create a minimal implementation and verify default doesn't throw
            ScreenFlasher minimal = new ScreenFlasher() {
                @Override
                public void flash(CalendarEvent event) { /* no-op */ }
                @Override
                public void flashMultiple(List<CalendarEvent> events) { /* no-op */ }
                @Override
                public void forceCleanup() { /* no-op */ }
            };
            assertDoesNotThrow(minimal::forceCleanup);
        }
    }
}
