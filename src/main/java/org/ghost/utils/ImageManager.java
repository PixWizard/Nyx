package org.ghost.utils;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

public class ImageManager {

    public static BufferedImage loadImage(String path) throws IOException {
        return ImageIO.read(new File(path));
    }

    public static BufferedImage loadAndPrepareCarrier(String path) throws IOException {
        BufferedImage img = ImageIO.read(new File(path));
        return (img != null) ? convertToARGB(img) : null;
    }

    public static void saveImage(BufferedImage image, String path) throws IOException {
        ImageIO.write(image, "png", new File(path));
    }

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