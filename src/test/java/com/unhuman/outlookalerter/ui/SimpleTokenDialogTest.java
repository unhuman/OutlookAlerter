package com.unhuman.outlookalerter.ui;

import com.unhuman.outlookalerter.util.LogManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for SimpleTokenDialog focusing on the auto-dismiss-on-background-auth
 * behaviour introduced to automatically close the login dialog when a background
 * silent re-authentication succeeds.
 */
class SimpleTokenDialogTest {

    @BeforeEach
    void setup() throws Exception {
        LogManager.getInstance();
        resetSingleton();
    }

    @AfterEach
    void cleanup() throws Exception {
        resetSingleton();
    }

    // ───────── BACKGROUND_AUTH_SUCCESS_KEY constant ─────────

    @Nested
    @DisplayName("BACKGROUND_AUTH_SUCCESS_KEY constant")
    class BackgroundAuthSuccessKeyTests {

        @Test
        @DisplayName("constant is defined and non-null")
        void constantIsDefined() {
            assertNotNull(SimpleTokenDialog.BACKGROUND_AUTH_SUCCESS_KEY);
            assertFalse(SimpleTokenDialog.BACKGROUND_AUTH_SUCCESS_KEY.isEmpty());
        }

        @Test
        @DisplayName("constant value is 'backgroundAuthSuccess'")
        void constantValue() {
            assertEquals("backgroundAuthSuccess", SimpleTokenDialog.BACKGROUND_AUTH_SUCCESS_KEY);
        }
    }

    // ───────── dismissWithBackgroundSuccess() ─────────

    @Nested
    @DisplayName("dismissWithBackgroundSuccess()")
    class DismissWithBackgroundSuccessTests {

        @Test
        @DisplayName("is a no-op when dialog is not showing")
        void noOpWhenNotShowing() {
            // Obtain a fresh instance that has never been shown
            SimpleTokenDialog dialog = SimpleTokenDialog.getInstance("http://example.com");
            // dismissWithBackgroundSuccess() must not throw when dialog is not visible
            assertDoesNotThrow(dialog::dismissWithBackgroundSuccess);
        }

        @Test
        @DisplayName("releases the latch so getTokens() returns promptly")
        void releasesLatch() throws Exception {
            SimpleTokenDialog dialog = SimpleTokenDialog.getInstance("http://example.com");
            // Simulate the isShowing flag being true (as if show() were called
            // successfully in a real environment) via reflection so we don't need
            // an actual Swing display
            setIsShowing(true);

            CountDownLatch testLatch = new CountDownLatch(1);
            AtomicReference<Map<String, String>> result = new AtomicReference<>();

            ExecutorService exec = Executors.newSingleThreadExecutor();
            exec.submit(() -> {
                result.set(dialog.getTokens());
                testLatch.countDown();
            });

            // Give getTokens() time to start waiting
            Thread.sleep(100);

            // Trigger background auth dismiss
            dialog.dismissWithBackgroundSuccess();

            // getTokens() should complete within 1 second
            assertTrue(testLatch.await(1, TimeUnit.SECONDS),
                    "getTokens() should return after dismissWithBackgroundSuccess()");

            Map<String, String> tokens = result.get();
            assertNotNull(tokens, "getTokens() must return a non-null map on background auth success");
            assertEquals("true", tokens.get(SimpleTokenDialog.BACKGROUND_AUTH_SUCCESS_KEY),
                    "Map must contain the BACKGROUND_AUTH_SUCCESS_KEY sentinel");
            assertFalse(tokens.containsKey("accessToken"),
                    "Map must NOT contain an accessToken (background auth, not manual entry)");

            exec.shutdownNow();
        }

        @Test
        @DisplayName("getTokens() sentinel map contains only the success key")
        void sentinelMapContainsOnlySuccessKey() throws Exception {
            SimpleTokenDialog dialog = SimpleTokenDialog.getInstance("http://example.com");
            setIsShowing(true);

            AtomicReference<Map<String, String>> result = new AtomicReference<>();
            CountDownLatch done = new CountDownLatch(1);

            Thread getter = new Thread(() -> {
                result.set(dialog.getTokens());
                done.countDown();
            });
            getter.setDaemon(true);
            getter.start();

            Thread.sleep(100);
            dialog.dismissWithBackgroundSuccess();

            assertTrue(done.await(1, TimeUnit.SECONDS));
            Map<String, String> tokens = result.get();
            assertNotNull(tokens);
            assertTrue(tokens.containsKey(SimpleTokenDialog.BACKGROUND_AUTH_SUCCESS_KEY));
            assertFalse(tokens.containsKey("accessToken"));
        }

        @Test
        @DisplayName("calling dismissWithBackgroundSuccess() twice does not throw")
        void idempotentSecondCall() throws Exception {
            SimpleTokenDialog dialog = SimpleTokenDialog.getInstance("http://example.com");
            setIsShowing(true);

            CountDownLatch done = new CountDownLatch(1);
            Thread getter = new Thread(() -> {
                dialog.getTokens();
                done.countDown();
            });
            getter.setDaemon(true);
            getter.start();

            Thread.sleep(100);
            dialog.dismissWithBackgroundSuccess(); // first call
            assertTrue(done.await(1, TimeUnit.SECONDS));

            // Second call (dialog already closed) must not throw
            assertDoesNotThrow(dialog::dismissWithBackgroundSuccess);
        }
    }

    // ───────── helpers ─────────

    /** Force the static singleton field back to null between tests. */
    private static void resetSingleton() throws Exception {
        Field instanceField = SimpleTokenDialog.class.getDeclaredField("instance");
        instanceField.setAccessible(true);
        Object existing = instanceField.get(null);
        if (existing != null) {
            try {
                ((SimpleTokenDialog) existing).dispose();
            } catch (Exception ignored) {
                // disposal may fail if no frame was created
            }
        }
        instanceField.set(null, null);

        Field showingField = SimpleTokenDialog.class.getDeclaredField("isShowing");
        showingField.setAccessible(true);
        showingField.set(null, false);
    }

    /**
     * Simulate the dialog being "shown" by flipping the static {@code isShowing} flag
     * so that {@link SimpleTokenDialog#dismissWithBackgroundSuccess()} recognises it as active.
     */
    private static void setIsShowing(boolean value) throws Exception {
        Field f = SimpleTokenDialog.class.getDeclaredField("isShowing");
        f.setAccessible(true);
        f.set(null, value);
    }
}
