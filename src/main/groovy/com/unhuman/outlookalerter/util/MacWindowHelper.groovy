package com.unhuman.outlookalerter.util

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Platform
import com.sun.jna.Pointer
import groovy.transform.CompileStatic

@CompileStatic
class MacWindowHelper {
    // Window level constants from NSWindow.h
    static final int NSNormalWindowLevel = 0
    static final int NSFloatingWindowLevel = 3
    static final int NSStatusWindowLevel = 25
    static final int NSPopUpMenuWindowLevel = 101
    static final int NSScreenSaverWindowLevel = 1000
    static final int NSMainMenuWindowLevel = 24
    static final int NSModalPanelWindowLevel = 8
    static final int NSOverlayWindowLevel = 102

    // Additional window style mask bits
    static final int NSWindowStyleMaskBorderless = 0
    static final int NSWindowStyleMaskTitled = 1
    static final int NSWindowStyleMaskClosable = 2
    static final int NSWindowStyleMaskMiniaturizable = 4
    static final int NSWindowStyleMaskResizable = 8
    static final int NSWindowStyleMaskUtilityWindow = 16
    static final int NSWindowStyleMaskDocModalWindow = 32
    static final int NSWindowStyleMaskNonactivatingPanel = 128
    static final int NSWindowStyleMaskHUDWindow = 8192

    // Collection flags
    static final int NSWindowCollectionBehaviorCanJoinAllSpaces = (1 << 0)
    static final int NSWindowCollectionBehaviorMoveToActiveSpace = (1 << 1)
    static final int NSWindowCollectionBehaviorManaged = (1 << 2)
    static final int NSWindowCollectionBehaviorTransient = (1 << 3)
    static final int NSWindowCollectionBehaviorStationary = (1 << 4)
    static final int NSWindowCollectionBehaviorParticipatesInCycle = (1 << 5)
    static final int NSWindowCollectionBehaviorIgnoresCycle = (1 << 6)
    static final int NSWindowCollectionBehaviorFullScreenPrimary = (1 << 7)
    static final int NSWindowCollectionBehaviorFullScreenAuxiliary = (1 << 8)
    static final int NSWindowCollectionBehaviorFullScreenAllowsTiling = (1 << 11)
    static final int NSWindowCollectionBehaviorFullScreenDisallowsTiling = (1 << 12)

    interface FoundationLib extends Library {
        FoundationLib INSTANCE = Native.load("Foundation", FoundationLib.class)
        Pointer objc_getClass(String className)
        Pointer sel_registerName(String selectorName)
        Pointer objc_msgSend(Pointer receiver, Pointer selector, Object... args)
    }

    static void setWindowLevel(long windowHandle, int level) {
        if (!Platform.isMac()) return

        try {
            FoundationLib foundation = FoundationLib.INSTANCE
            Pointer windowPtr = new Pointer(windowHandle)
            
            // Set window level to maximum possible value
            Pointer setLevelSelector = foundation.sel_registerName("setLevel:")
            foundation.objc_msgSend(windowPtr, setLevelSelector, level)

            // Set collection behavior to show above full screen apps
            Pointer setCollectionBehaviorSelector = foundation.sel_registerName("setCollectionBehavior:")
            int behavior = NSWindowCollectionBehaviorCanJoinAllSpaces | 
                          NSWindowCollectionBehaviorFullScreenAuxiliary |
                          NSWindowCollectionBehaviorStationary
            foundation.objc_msgSend(windowPtr, setCollectionBehaviorSelector, behavior)

            // Set window to float
            Pointer setFloatingSelector = foundation.sel_registerName("setFloatingPanel:")
            foundation.objc_msgSend(windowPtr, setFloatingSelector, 1)

            // Ensure window is visible and at front
            Pointer orderFrontSelector = foundation.sel_registerName("orderFrontRegardless")
            foundation.objc_msgSend(windowPtr, orderFrontSelector)

        } catch (Exception e) {
            System.err.println("Failed to set window level: " + e.getMessage())
        }
    }

    /**
     * Returns the maximum window level value on macOS
     */
    static final int CGMaximumWindowLevel = 2147483647  // Equivalent to CGMaximumWindowLevel

}
