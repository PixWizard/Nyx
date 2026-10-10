package org.ghost.core;

/**
 * Sequential embedding layout starting immediately after the header pixels.
 * Depth is fixed at 1 bit per RGB channel across all pixels.
 */
public class SequentialLayout implements EmbeddingLayout {

    public static final int HEADER_BYTES = 50;
    public static final int HEADER_BITS = HEADER_BYTES * 8; // 400 bits
    public static final int HEADER_PIXELS = (HEADER_BITS + 2) / 3; // 134 pixels

    private final int totalPixels;
    private final int startPixelIndex;
    private int currentPixelIndex;

    public SequentialLayout(int totalPixels) {
        this(totalPixels, HEADER_PIXELS);
    }

    public SequentialLayout(int totalPixels, int startPixelIndex) {
        this.totalPixels = Math.max(0, totalPixels);
        this.startPixelIndex = startPixelIndex;
        this.currentPixelIndex = startPixelIndex;
    }

    @Override
    public int nextPixelIndex() {
        if (currentPixelIndex < totalPixels) {
            return currentPixelIndex++;
        }
        return -1;
    }

    @Override
    public int depthAt(int pixelIndex) {
        return 1;
    }

    @Override
    public long capacityBits() {
        if (totalPixels <= startPixelIndex) {
            return 0L;
        }
        return (long) (totalPixels - startPixelIndex) * 3L;
    }

    public int getStartPixelIndex() {
        return startPixelIndex;
    }

    public int getTotalPixels() {
        return totalPixels;
    }
}
