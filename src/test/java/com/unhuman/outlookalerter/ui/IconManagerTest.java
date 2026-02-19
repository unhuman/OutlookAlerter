package com.unhuman.outlookalerter.ui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;

import java.awt.Image;
import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.*;

class IconManagerTest {

    @BeforeEach
    void setup() {
        IconManager.clearIconCaches();
    }

    // ───────── Icon Generation ─────────

    @Nested
    @DisplayName("createIconImage()")
    class CreateIconImage {

        @Test
        @DisplayName("creates 16x16 image")
        void creates16x16() {
            BufferedImage img = IconManager.createIconImage(16, false);
            assertNotNull(img);
            assertEquals(16, img.getWidth());
            assertEquals(16, img.getHeight());
        }

        @Test
        @DisplayName("creates 32x32 image")
        void creates32x32() {
            BufferedImage img = IconManager.createIconImage(32, false);
            assertNotNull(img);
            assertEquals(32, img.getWidth());
            assertEquals(32, img.getHeight());
        }

        @Test
        @DisplayName("image type is ARGB")
        void imageTypeArgb() {
            BufferedImage img = IconManager.createIconImage(16, false);
            assertEquals(BufferedImage.TYPE_INT_ARGB, img.getType());
        }

        @Test
        @DisplayName("valid icon has non-transparent pixels")
        void hasContent() {
            BufferedImage img = IconManager.createIconImage(16, false);
            boolean hasNonTransparent = false;
            for (int x = 0; x < img.getWidth(); x++) {
                for (int y = 0; y < img.getHeight(); y++) {
                    int alpha = (img.getRGB(x, y) >> 24) & 0xFF;
                    if (alpha > 0) {
                        hasNonTransparent = true;
                        break;
                    }
                }
                if (hasNonTransparent) break;
            }
            assertTrue(hasNonTransparent, "Icon should have visible content");
        }

        @Test
        @DisplayName("invalid token icon is different from valid icon")
        void differentForInvalidToken() {
            BufferedImage validIcon = IconManager.createIconImage(16, false);
            BufferedImage invalidIcon = IconManager.createIconImage(16, true);

            // At least some pixels should be different
            boolean hasDifference = false;
            for (int x = 0; x < 16; x++) {
                for (int y = 0; y < 16; y++) {
                    if (validIcon.getRGB(x, y) != invalidIcon.getRGB(x, y)) {
                        hasDifference = true;
                        break;
                    }
                }
                if (hasDifference) break;
            }
            assertTrue(hasDifference, "Valid and invalid icons should differ");
        }
    }

    // ───────── getIconImage ─────────

    @Nested
    @DisplayName("getIconImage()")
    class GetIconImage {

        @Test
        @DisplayName("returns non-null image")
        void returnsImage() {
            assertNotNull(IconManager.getIconImage());
        }

        @Test
        @DisplayName("returns cached image on second call")
        void caching() {
            Image img1 = IconManager.getIconImage();
            Image img2 = IconManager.getIconImage();
            assertSame(img1, img2);
        }

        @Test
        @DisplayName("invalid token icon is cached separately")
        void invalidCachedSeparately() {
            Image validIcon = IconManager.getIconImage(false);
            Image invalidIcon = IconManager.getIconImage(true);
            assertNotSame(validIcon, invalidIcon);
        }
    }

    // ───────── getLargeIconImage ─────────

    @Nested
    @DisplayName("getLargeIconImage()")
    class GetLargeIconImage {

        @Test
        @DisplayName("returns non-null image")
        void returnsImage() {
            assertNotNull(IconManager.getLargeIconImage());
        }

        @Test
        @DisplayName("returns cached image on second call")
        void caching() {
            Image img1 = IconManager.getLargeIconImage();
            Image img2 = IconManager.getLargeIconImage();
            assertSame(img1, img2);
        }

        @Test
        @DisplayName("large and small icons are different objects")
        void differentFromSmall() {
            Image small = IconManager.getIconImage();
            Image large = IconManager.getLargeIconImage();
            assertNotSame(small, large);
        }
    }

    // ───────── Cache Management ─────────

    @Nested
    @DisplayName("clearIconCaches()")
    class CacheClear {

        @Test
        @DisplayName("forces regeneration after clear")
        void regeneration() {
            Image first = IconManager.getIconImage();
            IconManager.clearIconCaches();
            Image second = IconManager.getIconImage();
            // After clearing, a new image is generated (different object)
            assertNotSame(first, second);
        }
    }
}
