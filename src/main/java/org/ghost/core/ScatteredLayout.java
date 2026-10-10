package org.ghost.core;

import org.ghost.crypto.ChaCha20Stream;

/**
 * Key-driven scattered embedding layout using lazy partial Fisher-Yates shuffle.
 * Header pixels (0..133) are excluded from the permutation pool and reserved for sequential header bits.
 * Depth is fixed at 1 bit per RGB channel across all payload pixels.
 */
public class ScatteredLayout implements EmbeddingLayout, AutoCloseable {

    /**
     * Maximum allowed image pixel count (33,554,432 pixels = 32 Mi-pixels).
     * An int[] of this size consumes exactly 128 MB of heap memory, ensuring safe
     * bounded memory allocation and preventing OutOfMemoryError on consumer environments.
     */
    public static final int MAX_PIXEL_COUNT = 33_554_432;

    private final int totalPixels;
    private final int poolSize;
    private final int[] pool;
    private int cursor;
    private final ChaCha20Stream stream;

    public ScatteredLayout(int totalPixels, byte[] scatterKey) {
        if (totalPixels > MAX_PIXEL_COUNT) {
            throw new IllegalArgumentException(String.format(
                    "Image pixel count %d exceeds maximum supported limit of %d pixels (guard against excessive heap usage)",
                    totalPixels, MAX_PIXEL_COUNT));
        }

        this.totalPixels = Math.max(0, totalPixels);
        int headerPixels = SequentialLayout.HEADER_PIXELS;
        if (this.totalPixels <= headerPixels) {
            this.poolSize = 0;
            this.pool = new int[0];
        } else {
            this.poolSize = this.totalPixels - headerPixels;
            this.pool = new int[this.poolSize];
            for (int i = 0; i < this.poolSize; i++) {
                this.pool[i] = headerPixels + i;
            }
        }
        this.cursor = 0;
        this.stream = new ChaCha20Stream(scatterKey);
    }

    @Override
    public int nextPixelIndex() {
        if (cursor >= poolSize) {
            throw new IllegalStateException("All payload pixels exhausted in scattered layout");
        }
        int i = cursor++;
        int j = i + stream.nextBounded(poolSize - i);
        int temp = pool[i];
        pool[i] = pool[j];
        pool[j] = temp;
        return pool[i];
    }

    @Override
    public int depthAt(int pixelIndex) {
        return 1;
    }

    @Override
    public long capacityBits() {
        return (long) poolSize * 3L;
    }

    public int getPoolSize() {
        return poolSize;
    }

    public int getTotalPixels() {
        return totalPixels;
    }

    @Override
    public void close() {
        stream.close();
    }
}
