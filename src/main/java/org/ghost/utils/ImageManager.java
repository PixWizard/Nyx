package org.ghost.utils;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

public class ImageManager {

    /**
     * Loads an image from the disk. 
     * NOTE: For Steganography, we must be careful with color management.
     */
    public static BufferedImage loadImage(String path) throws IOException {
        return ImageIO.read(new File(path));
    }

    /**
     * Loads an image and ENSURES it is in a format safe for bit-level manipulation (TYPE_INT_ARGB).
     * This is used when preparing a CARRIER image.
     */
    public static BufferedImage loadAndPrepareCarrier(String path) throws IOException {
        BufferedImage img = ImageIO.read(new File(path));
        return (img != null) ? convertToARGB(img) : null;
    }

    public static void saveImage(BufferedImage image, String path) throws IOException {
        ImageIO.write(image, "png", new File(path));
    }

    /**
     * Converts a BufferedImage to TYPE_INT_ARGB via drawing.
     * WARNING: This can be lossy if color management is active. 
     * Only use this for INITIAL CARRIER PREPARATION.
     */
    public static BufferedImage convertToARGB(BufferedImage original) {
        BufferedImage newImage = new BufferedImage(
                original.getWidth(), original.getHeight(), BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = newImage.createGraphics();
        // Use rendering hints to ensure visual consistency
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(original, 0, 0, null);
        g.dispose();
        return newImage;
    }
}