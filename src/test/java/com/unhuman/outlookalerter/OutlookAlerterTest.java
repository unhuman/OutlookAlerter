package com.unhuman.outlookalerter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class OutlookAlerterTest {

    @Test
    @DisplayName("isLaunchedByLaunchd is true when XPC_SERVICE_NAME matches the agent label")
    void launchedByLaunchd() {
        Map<String, String> env = new HashMap<>();
        env.put("XPC_SERVICE_NAME", OutlookAlerter.LAUNCHD_LABEL);
        assertTrue(OutlookAlerter.isLaunchedByLaunchd(env));
    }

    @Test
    @DisplayName("isLaunchedByLaunchd is false for Finder / login-item launches")
    void launchedByLaunchServices() {
        Map<String, String> env = new HashMap<>();
        env.put("XPC_SERVICE_NAME", "application.com.unhuman.outlookalerter.12345.67890");
        assertFalse(OutlookAlerter.isLaunchedByLaunchd(env));
        env.put("XPC_SERVICE_NAME", "0");
        assertFalse(OutlookAlerter.isLaunchedByLaunchd(env));
    }

    @Test
    @DisplayName("isLaunchedByLaunchd is false when XPC_SERVICE_NAME is absent or env is null")
    void missingEnv() {
        assertFalse(OutlookAlerter.isLaunchedByLaunchd(new HashMap<>()));
        assertFalse(OutlookAlerter.isLaunchedByLaunchd(null));
    }
}
