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
    private static final Color OUTLOOK_BLUE = new Color(0, 114, 198)
    private static final Color SIREN_RED = new Color(255, 0, 0, 180)
    private static BufferedImage iconCache
    private static BufferedImage largeIconCache
    
    /**
     * Get application icon image at standard size (16x16)
     */
    static Image getIconImage() {
        if (iconCache == null) {
            iconCache = createIconImage(16)
        }
        return iconCache
    }
    
    /**
     * Get large application icon image (32x32)
     */
    static Image getLargeIconImage() {
        if (largeIconCache == null) {
            largeIconCache = createIconImage(32)
        }
        return largeIconCache
    }
    
    /**
     * Creates a custom icon that looks like Outlook but with a siren light
     */
    private static BufferedImage createIconImage(int size) {
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
            g.setColor(OUTLOOK_BLUE)
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
            
            // Draw siren light (red dome) at the top
            g.setColor(SIREN_RED)
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
