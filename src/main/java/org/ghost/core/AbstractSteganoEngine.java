package org.ghost.core;

import java.awt.image.BufferedImage;

public abstract class AbstractSteganoEngine implements SteganoProtocol {

    protected int[] getImage(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();

        int[] pixelBuffer = new int[width * height];
        image.getRGB(0, 0, width, height, pixelBuffer, 0, width);

        return pixelBuffer;

    }

    public abstract int getBitsPerPixel();

}
