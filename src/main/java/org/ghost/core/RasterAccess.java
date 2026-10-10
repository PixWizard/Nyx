package org.ghost.core;

import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.awt.image.DataBuffer;
import java.awt.image.DataBufferInt;
import java.awt.image.Raster;
import java.awt.image.SinglePixelPackedSampleModel;

/**
 * Validates and provides direct raster array access for BufferedImage.TYPE_INT_ARGB images.
 *
 * SAFETY VALIDATION CRITERIA:
 * 1. image.getType() == BufferedImage.TYPE_INT_ARGB
 * 2. raster.getParent() == null && raster.getSampleModelTranslateX() == 0 && raster.getSampleModelTranslateY() == 0 (not a subimage)
 * 3. raster.getSampleModel() is SinglePixelPackedSampleModel with scanlineStride == width
 * 4. raster.getDataBuffer() is DataBufferInt with exactly 1 bank, offset 0, and array length == width * height
 * 5. ColorModel.isAlphaPremultiplied() == false
 *
 * If any criterion fails, directPixels returns null so the caller can fall back to a fresh canonical copy.
 */
public final class RasterAccess {

    private RasterAccess() {}

    /**
     * Returns the backing int[] of the image's raster if all safety criteria are satisfied;
     * returns null otherwise. Never throws for unusual images.
     */
    public static int[] directPixels(BufferedImage image) {
        if (image == null) {
            return null;
        }

        if (image.getType() != BufferedImage.TYPE_INT_ARGB) {
            return null;
        }

        Raster raster = image.getRaster();
        if (raster == null) {
            return null;
        }

        // Subimage checks: must not have a parent raster and must not have translated sample model coordinates
        if (raster.getParent() != null ||
                raster.getSampleModelTranslateX() != 0 ||
                raster.getSampleModelTranslateY() != 0) {
            return null;
        }

        int width = image.getWidth();
        int height = image.getHeight();

        // Sample model validation: must be SinglePixelPackedSampleModel with scanlineStride == width
        if (!(raster.getSampleModel() instanceof SinglePixelPackedSampleModel sppsm)) {
            return null;
        }
        if (sppsm.getScanlineStride() != width) {
            return null;
        }

        // Data buffer validation: must be DataBufferInt with 1 bank, offset 0, and length == width * height
        DataBuffer dataBuffer = raster.getDataBuffer();
        if (!(dataBuffer instanceof DataBufferInt dbi)) {
            return null;
        }
        if (dbi.getNumBanks() != 1 || dbi.getOffset() != 0) {
            return null;
        }

        int[] data = dbi.getData();
        if (data == null || data.length != width * height) {
            return null;
        }

        // Color model validation: must not have premultiplied alpha
        ColorModel colorModel = image.getColorModel();
        if (colorModel == null || colorModel.isAlphaPremultiplied()) {
            return null;
        }

        return data;
    }

    /**
     * Creates a fresh canonical copy of the input image in TYPE_INT_ARGB format
     * using Graphics2D with AlphaComposite.Src.
     */
    public static BufferedImage toCanonicalImage(BufferedImage image) {
        if (image == null) {
            throw new IllegalArgumentException("Image cannot be null");
        }
        int width = image.getWidth();
        int height = image.getHeight();
        BufferedImage canonical = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = canonical.createGraphics();
        try {
            g.setComposite(AlphaComposite.Src);
            g.drawImage(image, 0, 0, null);
        } finally {
            g.dispose();
        }
        return canonical;
    }
}
