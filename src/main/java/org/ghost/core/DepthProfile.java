package org.ghost.core;

/**
 * Encapsulates the gradient magnitude thresholds T1 < T2 < T3 used to determine bit depth.
 */
public record DepthProfile(byte id, int t1, int t2, int t3) {

    public static final byte DEFAULT_PROFILE_ID = 0x01;

    // Default gradient thresholds: T1 = 16, T2 = 48, T3 = 112
    public static final DepthProfile DEFAULT = new DepthProfile(DEFAULT_PROFILE_ID, 16, 48, 112);

    public DepthProfile {
        if (t1 >= t2 || t2 >= t3) {
            throw new IllegalArgumentException(String.format(
                    "Thresholds must be strictly ascending: t1 (%d) < t2 (%d) < t3 (%d)", t1, t2, t3));
        }
    }

    /**
     * Determines the bit depth for a given gradient magnitude G.
     * G < T1 -> 0, < T2 -> 1, < T3 -> 2, else 3.
     */
    public int depthForGradient(int g) {
        if (g < t1) return 0;
        if (g < t2) return 1;
        if (g < t3) return 2;
        return 3;
    }

    public static DepthProfile fromId(byte id) {
        if (id == DEFAULT_PROFILE_ID) {
            return DEFAULT;
        }
        throw new UnsupportedFormatException("Unknown depth profile id: " + id);
    }
}
