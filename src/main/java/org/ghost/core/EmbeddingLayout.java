package org.ghost.core;

/**
 * Strategy interface defining how payload bits are mapped to carrier pixels.
 */
public interface EmbeddingLayout {

    /**
     * Retrieves the next pixel index to be used for payload embedding or extraction.
     * Returns -1 if no further pixels are available.
     */
    int nextPixelIndex();

    /**
     * Returns the bit depth per channel at the given pixel index.
     */
    int depthAt(int pixelIndex);

    /**
     * Returns the total payload capacity in bits available in this layout.
     */
    long capacityBits();
}
