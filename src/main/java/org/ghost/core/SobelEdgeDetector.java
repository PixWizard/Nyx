package org.ghost.core;

/**
 * Sobel edge detector operating strictly on embedding-invariant high bits.
 *
 * THE CORE CORRECTNESS INVARIANT:
 * With maximum bit depth MAX_DEPTH = 3, embedding only ever modifies the lowest 3 bits
 * of the R, G, and B channels. By masking every color channel with INVARIANT_MASK (channel & 0xF8),
 * the computed luma and Sobel edge gradients are mathematically identical between the cover
 * image (prior to embedding) and the stego image (after embedding).
 * NOTE: Changing MAX_DEPTH changes INVARIANT_MASK and changes the file format.
 */
public final class SobelEdgeDetector {

    public static final int MAX_DEPTH = 3;
    public static final int INVARIANT_MASK = ~((1 << MAX_DEPTH) - 1) & 0xFF; // 0xF8 (248)

    private SobelEdgeDetector() {}

    /**
     * Computes the per-pixel depth mask for the image using Sobel edge detection.
     * Coordinate border handling: clamps coordinates to image boundaries (edge replication).
     */
    public static int[] computeMask(int[] pixelBuffer, int width, int height, DepthProfile profile) {
        int totalPixels = width * height;
        int[] luma = new int[totalPixels];

        // Compute luma using only invariant high bits: channel & 0xF8
        for (int i = 0; i < totalPixels; i++) {
            int pixel = pixelBuffer[i];
            int r = (pixel >>> 16) & INVARIANT_MASK;
            int g = (pixel >>> 8) & INVARIANT_MASK;
            int b = pixel & INVARIANT_MASK;
            luma[i] = (299 * r + 587 * g + 114 * b) / 1000; // Integer division
        }

        // Compute 3x3 Sobel gradient G = |Gx| + |Gy| with border coordinate clamping
        int[] mask = new int[totalPixels];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int g = computeGradientAt(luma, width, height, x, y);
                mask[y * width + x] = profile.depthForGradient(g);
            }
        }
        return mask;
    }

    /**
     * Computes Sobel gradient magnitude at (x, y) with coordinate clamping (edge replication).
     */
    public static int computeGradientAt(int[] luma, int width, int height, int x, int y) {
        // Clamped 3x3 neighborhood:
        // l00 = (-1, -1), l10 = (0, -1), l20 = (1, -1)
        // l01 = (-1,  0), l11 = (0,  0), l21 = (1,  0)
        // l02 = (-1,  1), l12 = (0,  1), l22 = (1,  1)
        int l00 = getClamped(luma, width, height, x - 1, y - 1);
        int l10 = getClamped(luma, width, height, x,     y - 1);
        int l20 = getClamped(luma, width, height, x + 1, y - 1);

        int l01 = getClamped(luma, width, height, x - 1, y);
        int l11 = getClamped(luma, width, height, x,     y);
        int l21 = getClamped(luma, width, height, x + 1, y);

        int l02 = getClamped(luma, width, height, x - 1, y + 1);
        int l12 = getClamped(luma, width, height, x,     y + 1);
        int l22 = getClamped(luma, width, height, x + 1, y + 1);

        // Gx kernel: [[-1, 0, 1], [-2, 0, 2], [-1, 0, 1]]
        int gx = (-l00 + l20) + 2 * (-l01 + l21) + (-l02 + l22);

        // Gy kernel: [[-1, -2, -1], [0, 0, 0], [1, 2, 1]]
        int gy = (-l00 - 2 * l10 - l20) + (l02 + 2 * l12 + l22);

        return Math.abs(gx) + Math.abs(gy);
    }

    private static int getClamped(int[] luma, int width, int height, int x, int y) {
        int cx = Math.max(0, Math.min(width - 1, x));
        int cy = Math.max(0, Math.min(height - 1, y));
        return luma[cy * width + cx];
    }
}
