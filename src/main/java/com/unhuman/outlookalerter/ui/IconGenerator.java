package com.unhuman.outlookalerter.ui;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import com.unhuman.outlookalerter.util.LogManager;
import com.unhuman.outlookalerter.util.LogCategory;

/**
 * Generates a static PNG file for the application icon.
 */
public class IconGenerator {
    public static void main(String[] args) {
        int[] sizes = {16, 32};
        for (int size : sizes) {
            try {
                BufferedImage validIcon = IconManager.createIconImage(size, false);
                BufferedImage invalidIcon = IconManager.createIconImage(size, true);

                File validFile = new File("target/icon-valid-" + size + "x" + size + ".png");
                ImageIO.write(validIcon, "png", validFile);
                LogManager.getInstance().info(LogCategory.GENERAL, "Saved valid icon: " + validFile.getAbsolutePath());

                File invalidFile = new File("target/icon-invalid-" + size + "x" + size + ".png");
                ImageIO.write(invalidIcon, "png", invalidFile);
                LogManager.getInstance().info(LogCategory.GENERAL, "Saved invalid icon: " + invalidFile.getAbsolutePath());
            } catch (Exception e) {
                LogManager.getInstance().error(LogCategory.GENERAL, "Error generating icons: " + e.getMessage());
            }
        }
    }
}
