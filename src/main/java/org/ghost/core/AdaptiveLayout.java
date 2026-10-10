package org.ghost.core;

/**
 * Edge-adaptive embedding layout that modulates bit depth per pixel based on Sobel edge gradients.
 * Wraps an underlying position source (SequentialLayout or ScatteredLayout).
 * Pixels with depth 0 are skipped during payload embedding and extraction.
 */
public class AdaptiveLayout implements EmbeddingLayout, AutoCloseable {

    private final EmbeddingLayout positionSource;
    private final int[] mask;
    private final long capacityBits;

    public AdaptiveLayout(EmbeddingLayout positionSource, int[] mask, int width, int height) {
        this.positionSource = positionSource;
        this.mask = mask;

        // Header pixels (0..133) are excluded from the pool.
        // Capacity is the sum over pool pixels (134..N-1) of 3 bits * depth.
        long totalCapacity = 0L;
        int totalPixels = width * height;
        for (int p = SequentialLayout.HEADER_PIXELS; p < totalPixels; p++) {
            totalCapacity += 3L * (long) mask[p];
        }
        this.capacityBits = totalCapacity;
    }

    @Override
    public int nextPixelIndex() {
        return positionSource.nextPixelIndex();
    }

    @Override
    public int depthAt(int pixelIndex) {
        return mask[pixelIndex];
    }

    @Override
    public long capacityBits() {
        return capacityBits;
    }

    @Override
    public void close() {
        if (positionSource instanceof AutoCloseable ac) {
            try {
                ac.close();
            } catch (Exception ignored) {}
        }
    }
}
