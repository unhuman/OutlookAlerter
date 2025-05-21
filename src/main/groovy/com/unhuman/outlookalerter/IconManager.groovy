package com.unhuman.outlookalerter

import groovy.transform.CompileStatic
import java.awt.*
import java.awt.geom.*
import java.awt.image.BufferedImage

/**
 * Manages custom icon generation for OutlookAlerter
 */
@CompileStatic
class IconManager {
    private static final Color OUTLOOK_BLUE = new Color(0, 114, 198) // Microsoft Outlook blue
    private static final Color ALERT_RED = new Color(220, 0, 0) // Bright red for better visibility
    private static BufferedImage iconCache
    private static BufferedImage largeIconCache
    private static BufferedImage invalidIconCache
    private static BufferedImage invalidLargeIconCache
    
    /**
     * Clear all icon caches to force regeneration of icons
     */
    static void clearIconCaches() {
        // System.out.println("ICON: Clearing all icon caches to force regeneration")
        iconCache = null
        largeIconCache = null
        invalidIconCache = null
        invalidLargeIconCache = null
    }
    
    /**
     * Get application icon image at standard size (16x16)
     */
    static Image getIconImage(boolean isTokenInvalid = false) {
        // System.out.println("ICON: Requesting ${isTokenInvalid ? 'invalid' : 'valid'} state icon at 16x16")
        if (isTokenInvalid) {
            if (invalidIconCache == null) {
                // System.out.println("ICON: Creating new invalid state icon at 16x16")
                invalidIconCache = createIconImage(16, true)
            }
            return invalidIconCache
        } else {
            if (iconCache == null) {
                // System.out.println("ICON: Creating new valid state icon at 16x16")
                iconCache = createIconImage(16, false)
            }
            return iconCache
        }
    }
    
    /**
     * Get large application icon image (32x32)
     */
    static Image getLargeIconImage(boolean isTokenInvalid = false) {
        // System.out.println("ICON: Requesting ${isTokenInvalid ? 'invalid' : 'valid'} state icon at 32x32")
        if (isTokenInvalid) {
            if (invalidLargeIconCache == null) {
                // System.out.println("ICON: Creating new invalid state icon at 32x32")
                invalidLargeIconCache = createIconImage(32, true)
            }
            return invalidLargeIconCache
        } else {
            if (largeIconCache == null) {
                // System.out.println("ICON: Creating new valid state icon at 32x32")
                largeIconCache = createIconImage(32, false)
            }
            return largeIconCache
        }
    }
    
    /**
     * Creates a custom icon that looks like Outlook but with a siren light
     * @param size The size of the icon (16 or 32)
     * @param isTokenInvalid If true, inverts the blue and red colors
     */
    private static BufferedImage createIconImage(int size, boolean isTokenInvalid) {
        // System.out.println("ICON: Creating new icon with size=${size}, isTokenInvalid=${isTokenInvalid}")
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        Graphics2D g = img.createGraphics()
        
        try {
            // Enable anti-aliasing
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            
            // Calculate sizes based on icon size
            float scale = (float)(size / 16.0)
            float margin = scale
            float cornerRadius = 2.0f * scale
            float sirenSize = 6.0f * scale
            
            // Draw Outlook-like base (rounded rectangle)
            // Default/Valid state: Blue O (like Outlook)
            // Invalid state: Red O (calls for attention)
            g.setColor(isTokenInvalid ? ALERT_RED : OUTLOOK_BLUE)
            float y = (float)(margin + sirenSize/2.0f)
            float w = (float)(size - 2.0f*margin)
            float h = (float)(size - 2.0f*margin - sirenSize/2.0f)
            RoundRectangle2D.Float baseRect = new RoundRectangle2D.Float(
                margin,
                y,
                w,
                h,
                cornerRadius,
                cornerRadius
            )
            g.fill(baseRect)
            
            // Draw letter "O" in white
            g.setColor(Color.WHITE)
            g.setFont(new Font("Arial", Font.BOLD, Math.round(9.0f * scale)))
            FontMetrics fm = g.getFontMetrics()
            String letter = "O"
            Rectangle2D textBounds = fm.getStringBounds(letter, g)
            g.drawString(letter,
                (float)((size - textBounds.getWidth())/2),
                (float)(size - (size - textBounds.getHeight())/2 - 1)
            )
            
            // Draw siren light
            // Default/Valid state: Red siren (standby alert)
            // Invalid state: Blue siren (de-emphasized)
            g.setColor(isTokenInvalid ? OUTLOOK_BLUE : ALERT_RED)
            Arc2D.Float sirenArc = new Arc2D.Float(
                (float)((size - sirenSize)/2),
                margin,
                sirenSize,
                sirenSize,
                0.0f,
                180.0f,
                Arc2D.PIE
            )
            g.fill(sirenArc)
            
            // Add shine to siren (small white arc) 
            g.setColor(new Color(255, 255, 255, 100))
            float shineSize = sirenSize * 0.6f
            Arc2D.Float shineArc = new Arc2D.Float(
                (float)((size - shineSize)/2),
                margin + 1.0f,
                shineSize,
                shineSize,
                20.0f,
                140.0f,
                Arc2D.PIE
            )
            g.fill(shineArc)
        } finally {
            g.dispose()
        }
        
        return img
    }
}
