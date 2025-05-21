package com.unhuman.outlookalerter

import javax.imageio.ImageIO
import java.io.File

/**
 * Generates a static PNG file for the application icon.
 */
class IconGenerator {
    static void main(String[] args) {
        // Generate icons at 16x16 and 32x32 sizes
        def sizes = [16, 32]
        sizes.each { size ->
            def validIcon = IconManager.createIconImage(size, false)
            def invalidIcon = IconManager.createIconImage(size, true)

            // Save valid icon
            def validFile = new File("target/icon-valid-${size}x${size}.png")
            ImageIO.write(validIcon, "png", validFile)
            println "Saved valid icon: ${validFile.absolutePath}"

            // Save invalid icon
            def invalidFile = new File("target/icon-invalid-${size}x${size}.png")
            ImageIO.write(invalidIcon, "png", invalidFile)
            println "Saved invalid icon: ${invalidFile.absolutePath}"
        }
    }
}
