package com.unhuman.outlookalerter.ui;

import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;

/**
 * Manages custom icon generation for OutlookAlerter
 */
public class IconManager {
    private static final Color OUTLOOK_BLUE = new Color(0, 114, 198);
    private static final Color ALERT_RED = new Color(220, 0, 0);
    private static BufferedImage iconCache;
    private static BufferedImage largeIconCache;
    private static BufferedImage invalidIconCache;
    private static BufferedImage invalidLargeIconCache;

    public static void clearIconCaches() {
        iconCache = null;
        largeIconCache = null;
        invalidIconCache = null;
        invalidLargeIconCache = null;
    }

    public static Image getIconImage(boolean isTokenInvalid) {
        if (isTokenInvalid) {
            if (invalidIconCache == null) {
                invalidIconCache = createIconImage(16, true);
            }
            return invalidIconCache;
        } else {
            if (iconCache == null) {
                iconCache = createIconImage(16, false);
            }
            return iconCache;
        }
    }

    public static Image getIconImage() {
        return getIconImage(false);
    }

    public static Image getLargeIconImage(boolean isTokenInvalid) {
        if (isTokenInvalid) {
            if (invalidLargeIconCache == null) {
                invalidLargeIconCache = createIconImage(32, true);
            }
            return invalidLargeIconCache;
        } else {
            if (largeIconCache == null) {
                largeIconCache = createIconImage(32, false);
            }
            return largeIconCache;
        }
    }

    public static Image getLargeIconImage() {
        return getLargeIconImage(false);
    }

    public static BufferedImage createIconImage(int size, boolean isTokenInvalid) {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();

        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            float scale = (float)(size / 16.0);
            float margin = scale;
            float cornerRadius = 2.0f * scale;
            float sirenSize = 6.0f * scale;

            g.setColor(isTokenInvalid ? ALERT_RED : OUTLOOK_BLUE);
            float y = (float)(margin + sirenSize / 2.0f);
            float w = (float)(size - 2.0f * margin);
            float h = (float)(size - 2.0f * margin - sirenSize / 2.0f);
            RoundRectangle2D.Float baseRect = new RoundRectangle2D.Float(
                margin, y, w, h, cornerRadius, cornerRadius
            );
            g.fill(baseRect);

            g.setColor(Color.WHITE);
            g.setFont(new Font("Arial", Font.BOLD, Math.round(9.0f * scale)));
            FontMetrics fm = g.getFontMetrics();
            String letter = "O";
            Rectangle2D textBounds = fm.getStringBounds(letter, g);
            g.drawString(letter,
                (float)((size - textBounds.getWidth()) / 2),
                (float)(size - (size - textBounds.getHeight()) / 2 - 1)
            );

            g.setColor(isTokenInvalid ? OUTLOOK_BLUE : ALERT_RED);
            Arc2D.Float sirenArc = new Arc2D.Float(
                (float)((size - sirenSize) / 2),
                margin,
                sirenSize,
                sirenSize,
                0.0f,
                180.0f,
                Arc2D.PIE
            );
            g.fill(sirenArc);

            g.setColor(new Color(255, 255, 255, 100));
            float shineSize = sirenSize * 0.6f;
            Arc2D.Float shineArc = new Arc2D.Float(
                (float)((size - shineSize) / 2),
                margin + 1.0f,
                shineSize,
                shineSize,
                20.0f,
                140.0f,
                Arc2D.PIE
            );
            g.fill(shineArc);
        } finally {
            g.dispose();
        }

        return img;
    }
}
