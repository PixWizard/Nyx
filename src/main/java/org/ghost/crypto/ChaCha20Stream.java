package org.ghost.crypto;

import javax.crypto.Cipher;
import javax.crypto.spec.ChaCha20ParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;
import java.util.Arrays;

/**
 * Deterministic PRNG stream backed by JDK's ChaCha20 cipher.
 * Produces unbiased pseudo-random unsigned 32-bit integers and bounded integers via rejection sampling.
 */
public final class ChaCha20Stream implements AutoCloseable {

    private static final int BLOCK_CHUNK_SIZE = 4096;
    private static final long TWO_POW_32 = 1L << 32;

    private final Cipher cipher;
    private final byte[] zeroBlock = new byte[BLOCK_CHUNK_SIZE];
    private byte[] buffer;
    private int bufferPos;

    public ChaCha20Stream(byte[] key) {
        if (key == null || key.length != 32) {
            throw new IllegalArgumentException("ChaCha20 key must be exactly 32 bytes");
        }
        try {
            byte[] nonce = new byte[12]; // Fixed 12-byte zero nonce
            ChaCha20ParameterSpec spec = new ChaCha20ParameterSpec(nonce, 0);
            this.cipher = Cipher.getInstance("ChaCha20");
            this.cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "ChaCha20"), spec);
            this.buffer = new byte[0];
            this.bufferPos = 0;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Failed to initialize ChaCha20 cipher", e);
        }
    }

    private byte nextByte() {
        if (bufferPos >= buffer.length) {
            try {
                buffer = cipher.update(zeroBlock);
                bufferPos = 0;
            } catch (Exception e) {
                throw new IllegalStateException("ChaCha20 keystream generation failed", e);
            }
        }
        return buffer[bufferPos++];
    }

    /**
     * Consumes 4 bytes from the keystream and returns an unsigned 32-bit integer (big-endian).
     */
    public long nextU32() {
        long b0 = nextByte() & 0xFFL;
        long b1 = nextByte() & 0xFFL;
        long b2 = nextByte() & 0xFFL;
        long b3 = nextByte() & 0xFFL;
        return ((b0 << 24) | (b1 << 16) | (b2 << 8) | b3) & 0xFFFFFFFFL;
    }

    /**
     * Produces an unbiased random integer in [0, bound) using rejection sampling.
     */
    public int nextBounded(int bound) {
        if (bound <= 0) {
            throw new IllegalArgumentException("Bound must be positive, got: " + bound);
        }
        if (bound == 1) {
            return 0;
        }
        long limit = (TWO_POW_32 / bound) * bound;
        long r;
        do {
            r = nextU32();
        } while (r >= limit);

        return (int) (r % bound);
    }

    @Override
    public void close() {
        if (buffer != null) {
            Arrays.fill(buffer, (byte) 0);
        }
        Arrays.fill(zeroBlock, (byte) 0);
    }
}
