package org.ghost.utils;

import javax.imageio.ImageIO;
import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

public class ImageManager {

    /**
     * Loads an image from disk.
     * Throws clear IOException if file not found or if format is unsupported/corrupt.
     */
    public static BufferedImage loadImage(String path) throws IOException {
        File file = new File(path);
        if (!file.exists()) {
            throw new IOException("Image file not found: " + path);
        }
        BufferedImage img = ImageIO.read(file);
        if (img == null) {
            throw new IOException("Unsupported or corrupt image format: " + path);
        }
        return img;
    }

    /**
     * Loads an image and converts it to TYPE_INT_ARGB format for bit manipulation.
     */
    public static BufferedImage loadAndPrepareCarrier(String path) throws IOException {
        BufferedImage img = loadImage(path);
        return convertToARGB(img);
    }

    public static void saveImage(BufferedImage image, String path) throws IOException {
        if (image == null) {
            throw new IllegalArgumentException("Image cannot be null");
        }
        boolean ok = ImageIO.write(image, "png", new File(path));
        if (!ok) {
            throw new IOException("Failed to save image: no suitable PNG ImageWriter found for " + path);
        }
    }

    /**
     * Converts a BufferedImage to TYPE_INT_ARGB using AlphaComposite.Src.
     */
    public static BufferedImage convertToARGB(BufferedImage original) {
        if (original == null) {
            throw new IllegalArgumentException("Original image cannot be null");
        }
        if (original.getType() == BufferedImage.TYPE_INT_ARGB) {
            return original;
        }
        BufferedImage newImage = new BufferedImage(
                original.getWidth(), original.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = newImage.createGraphics();
        try {
            g.setComposite(AlphaComposite.Src);
            g.drawImage(original, 0, 0, null);
        } finally {
            g.dispose();
        }
        return newImage;
    }
}