package org.ghost.core;

import org.ghost.crypto.AuthenticatedCipher;
import org.ghost.crypto.AuthenticationFailedException;
import org.ghost.crypto.NyxKeyDerivation;

import java.awt.image.BufferedImage;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * Core steganography engine implementing 3-channel LSB embedding with authenticated envelope header,
 * Sobel edge-adaptive depth modulation, and key-driven scattered or sequential embedding layouts.
 */
public class SteganoEngine extends AbstractSteganoEngine {

    public static final long ARGON2_MEMORY_SAFETY_MARGIN_BYTES = 64L * 1024L * 1024L; // 64 MB heap safety margin

    private final SecureRandom secureRandom = new SecureRandom();

    public static void checkArgon2MemorySafety(long memoryKiB) {
        long requiredBytes = memoryKiB * 1024L + ARGON2_MEMORY_SAFETY_MARGIN_BYTES;
        long maxMemory = Runtime.getRuntime().maxMemory();
        if (requiredBytes > maxMemory) {
            throw new InsufficientMemoryException(String.format(
                    "Required Argon2 memory (%d KiB) plus safety margin (%d KiB) exceeds JVM maximum heap (%d KiB). Please raise -Xmx.",
                    memoryKiB, ARGON2_MEMORY_SAFETY_MARGIN_BYTES / 1024L, maxMemory / 1024L));
        }
    }

    /**
     * Legacy protocol compatibility method.
     * Wraps payload in an authenticated envelope with default scattering and constant depth 1.
     */
    private static final char[] LEGACY_DEFAULT_PASSWORD = "org.ghost.legacy.protocol.default.passphrase".toCharArray();

    @Override
    public void cloak(BufferedImage image, byte[] data) {
        cloak(image, data, "", LEGACY_DEFAULT_PASSWORD.clone(), true, false);
    }

    @Override
    public byte[] reveal(BufferedImage image) {
        PayloadEnvelope.Unpacked unpacked = reveal(image, LEGACY_DEFAULT_PASSWORD.clone());
        return unpacked.fileBytes();
    }

    /**
     * Encrypts and embeds file payload with metadata into the carrier image.
     * Scattering is enabled by default, depth is constant depth 1.
     */
    public BufferedImage cloak(BufferedImage image, byte[] fileBytes, String extension, char[] password) {
        return cloak(image, fileBytes, extension, password, true, false);
    }

    /**
     * Encrypts and embeds file payload with metadata, allowing control over scattering (depth is constant depth 1).
     */
    public BufferedImage cloak(BufferedImage image, byte[] fileBytes, String extension, char[] password, boolean scatter) {
        return cloak(image, fileBytes, extension, password, scatter, false);
    }

    /**
     * Encrypts and embeds file payload with metadata into the carrier image,
     * with explicit control over scattering and edge-adaptive depth allocation.
     * Uses direct raster array access if image passes safety validation, avoiding getRGB/setRGB copies.
     * Returns the stego image (which is the input image if direct, or a canonical copy if fallback).
     */
    public BufferedImage cloak(BufferedImage image, byte[] fileBytes, String extension, char[] password,
                               boolean scatter, boolean adaptive) {
        byte[] salt = new byte[NyxKeyDerivation.SALT_LENGTH_BYTES];
        byte[] nonce = new byte[NyxKeyDerivation.NONCE_LENGTH_BYTES];
        secureRandom.nextBytes(salt);
        secureRandom.nextBytes(nonce);
        return cloakWithFixedCrypto(image, fileBytes, extension, password, scatter, adaptive, salt, nonce);
    }

    /**
     * Package-private seam for deterministic equivalence testing with injected salt and nonce.
     */
    BufferedImage cloakWithFixedCrypto(BufferedImage image, byte[] fileBytes, String extension, char[] password,
                                       boolean scatter, boolean adaptive, byte[] salt, byte[] nonce) {
        return cloakInternal(image, fileBytes, extension, password, scatter, adaptive, salt, nonce,
                NyxKeyDerivation.DEFAULT_ARGON2_MEMORY_KIB,
                NyxKeyDerivation.DEFAULT_ARGON2_ITERATIONS,
                NyxKeyDerivation.DEFAULT_ARGON2_PARALLELISM);
    }

    /**
     * Package-private test seam allowing customized Argon2 parameters for fast fuzz testing and benchmarks.
     */
    BufferedImage cloakWithCustomArgon2(BufferedImage image, byte[] fileBytes, String extension, char[] password,
                                        boolean scatter, boolean adaptive, int memoryKiB, int iterations, int parallelism) {
        byte[] salt = new byte[NyxKeyDerivation.SALT_LENGTH_BYTES];
        byte[] nonce = new byte[NyxKeyDerivation.NONCE_LENGTH_BYTES];
        secureRandom.nextBytes(salt);
        secureRandom.nextBytes(nonce);
        return cloakInternal(image, fileBytes, extension, password, scatter, adaptive, salt, nonce, memoryKiB, iterations, parallelism);
    }

    private BufferedImage cloakInternal(BufferedImage image, byte[] fileBytes, String extension, char[] password,
                                        boolean scatter, boolean adaptive, byte[] salt, byte[] nonce,
                                        int memoryKiB, int iterations, int parallelism) {
        if (image == null) {
            throw new IllegalArgumentException("Carrier image cannot be null");
        }
        if (password == null || password.length == 0) {
            throw new IllegalArgumentException("Password cannot be null or empty");
        }

        // Direct raster fast path: if image passes validation, use backing array directly.
        // Otherwise, fall back to a fresh canonical copy.
        int[] directPixels = RasterAccess.directPixels(image);
        BufferedImage carrier = (directPixels != null) ? image : RasterAccess.toCanonicalImage(image);
        int[] pixelBuffer = (directPixels != null) ? directPixels : RasterAccess.directPixels(carrier);

        // Pack metadata payload envelope
        byte[] plaintext = PayloadEnvelope.pack(fileBytes, extension);

        long ciphertextLength = (long) plaintext.length + AuthenticatedCipher.GCM_TAG_LENGTH_BYTES;

        // Verify carrier capacity before modification
        int width = carrier.getWidth();
        int height = carrier.getHeight();
        int totalPixels = width * height;
        if (totalPixels < SequentialLayout.HEADER_PIXELS) {
            throw new InsufficientCarrierCapacityException(ciphertextLength, 0L);
        }

        DepthProfile profile = DepthProfile.DEFAULT;
        int[] mask = adaptive ? SobelEdgeDetector.computeMask(pixelBuffer, width, height, profile) : null;

        long capacityBits;
        if (adaptive) {
            long totalCap = 0L;
            for (int p = SequentialLayout.HEADER_PIXELS; p < totalPixels; p++) {
                totalCap += 3L * (long) mask[p];
            }
            capacityBits = totalCap;
        } else {
            capacityBits = (long) (totalPixels - SequentialLayout.HEADER_PIXELS) * 3L;
        }

        long availableCapacityBytes = capacityBits / 8L;
        long requiredBits = ciphertextLength * 8L;

        if (requiredBits > capacityBits) {
            throw new InsufficientCarrierCapacityException(ciphertextLength, availableCapacityBytes);
        }

        // Serialize envelope header
        byte flags = 0;
        if (scatter) flags |= EnvelopeHeader.FLAG_SCATTERED;
        if (adaptive) flags |= EnvelopeHeader.FLAG_ADAPTIVE;
        byte profileId = adaptive ? profile.id() : (byte) 0;

        EnvelopeHeader header = new EnvelopeHeader(
                EnvelopeHeader.CIPHER_ID_AES_256_GCM,
                EnvelopeHeader.KDF_ID_ARGON2ID,
                flags,
                profileId,
                memoryKiB,
                iterations,
                parallelism,
                salt,
                nonce,
                ciphertextLength
        );
        byte[] serializedHeader = header.serialize();
        byte[] aad = Arrays.copyOfRange(serializedHeader, 0, EnvelopeHeader.AAD_SIZE_BYTES);

        checkArgon2MemorySafety(memoryKiB);

        // Derive cryptographic keys and encrypt payload
        byte[] ciphertext;
        byte[] scatterKey;
        try (NyxKeyDerivation.DerivedKeys keys = NyxKeyDerivation.deriveKeys(password, salt, memoryKiB, iterations, parallelism)) {
            ciphertext = AuthenticatedCipher.encrypt(plaintext, keys.aesKey(), nonce, aad);
            scatterKey = Arrays.copyOf(keys.scatterKey(), keys.scatterKey().length);
        } finally {
            Arrays.fill(plaintext, (byte) 0);
        }

        // Embed header and payload into pixel buffer
        try {
            EmbeddingLayout positionSource = scatter
                    ? new ScatteredLayout(totalPixels, scatterKey)
                    : new SequentialLayout(totalPixels);

            EmbeddingLayout layout = adaptive
                    ? new AdaptiveLayout(positionSource, mask, width, height)
                    : positionSource;

            embedHeader(pixelBuffer, serializedHeader);
            embedPayload(pixelBuffer, ciphertext, layout);
            if (positionSource instanceof ScatteredLayout sl) {
                sl.close();
            }
        } finally {
            Arrays.fill(scatterKey, (byte) 0);
        }

        return carrier;
    }

    /**
     * Extracts and decrypts payload from a carrier image.
     * Automatically selects layout, scattering, and depth profile based on header flags.
     * Uses direct raster array access if image passes safety validation, avoiding getRGB copies.
     */
    public PayloadEnvelope.Unpacked reveal(BufferedImage image, char[] password) {
        if (image == null) {
            throw new IllegalArgumentException("Carrier image cannot be null");
        }
        if (password == null || password.length == 0) {
            throw new IllegalArgumentException("Password cannot be null or empty");
        }

        int width = image.getWidth();
        int height = image.getHeight();
        int totalPixels = width * height;
        if (totalPixels < SequentialLayout.HEADER_PIXELS) {
            throw new NoNyxPayloadException("Carrier image too small to contain a Nyx header");
        }

        int[] directPixels = RasterAccess.directPixels(image);
        BufferedImage carrier = (directPixels != null) ? image : RasterAccess.toCanonicalImage(image);
        int[] pixelBuffer = (directPixels != null) ? directPixels : RasterAccess.directPixels(carrier);

        // Extract raw header from fixed sequential header pixels
        byte[] rawHeader = extractHeader(pixelBuffer);

        // Theoretical maximum capacity with MAX_DEPTH = 3
        long maxTheoreticalCapacityBytes = ((long) (totalPixels - SequentialLayout.HEADER_PIXELS) * 3L * (long) SobelEdgeDetector.MAX_DEPTH) / 8L;
        EnvelopeHeader header = EnvelopeHeader.deserialize(rawHeader, maxTheoreticalCapacityBytes);

        boolean isScattered = (header.getFlags() & EnvelopeHeader.FLAG_SCATTERED) != 0;
        boolean isAdaptive = (header.getFlags() & EnvelopeHeader.FLAG_ADAPTIVE) != 0;

        int[] mask = null;
        long realCapacityBits;
        if (isAdaptive) {
            DepthProfile profile = DepthProfile.fromId(header.getProfileId());
            mask = SobelEdgeDetector.computeMask(pixelBuffer, width, height, profile);
            long totalCap = 0L;
            for (int p = SequentialLayout.HEADER_PIXELS; p < totalPixels; p++) {
                totalCap += 3L * (long) mask[p];
            }
            realCapacityBits = totalCap;
        } else {
            realCapacityBits = (long) (totalPixels - SequentialLayout.HEADER_PIXELS) * 3L;
        }

        if (header.getCiphertextLength() * 8L > realCapacityBits) {
            throw new InvalidHeaderException(String.format(
                    "Ciphertext length %d bytes exceeds real carrier capacity of %d bytes for active layout",
                    header.getCiphertextLength(), realCapacityBits / 8L));
        }

        // Memory safety check before Argon2
        checkArgon2MemorySafety(header.getArgon2MemoryKiB());

        // Derive decryption keys
        byte[] serializedHeader = header.serialize();
        byte[] aad = Arrays.copyOfRange(serializedHeader, 0, EnvelopeHeader.AAD_SIZE_BYTES);

        byte[] plaintext;
        try (NyxKeyDerivation.DerivedKeys keys = NyxKeyDerivation.deriveKeys(
                password, header.getSalt(), header.getArgon2MemoryKiB(),
                header.getArgon2Iterations(), header.getArgon2Parallelism())) {

            EmbeddingLayout positionSource = isScattered
                    ? new ScatteredLayout(totalPixels, keys.scatterKey())
                    : new SequentialLayout(totalPixels);

            EmbeddingLayout layout = isAdaptive
                    ? new AdaptiveLayout(positionSource, mask, width, height)
                    : positionSource;

            try {
                // Extract ciphertext using active layout
                byte[] ciphertext = extractPayload(pixelBuffer, header.getCiphertextLength(), layout);

                // Decrypt and verify payload
                plaintext = AuthenticatedCipher.decrypt(ciphertext, keys.aesKey(), header.getNonce(), aad);
            } finally {
                if (positionSource instanceof ScatteredLayout sl) {
                    sl.close();
                }
            }
        }

        // Unpack payload metadata and clear plaintext
        byte[] finalPlaintext = plaintext;
        try {
            return PayloadEnvelope.unpack(plaintext);
        } catch (IllegalArgumentException e) {
            throw new AuthenticationFailedException("Corrupted payload framing: " + e.getMessage(), e);
        } finally {
            if (finalPlaintext != null) {
                Arrays.fill(finalPlaintext, (byte) 0);
            }
        }
    }

    /**
     * Embeds a raw header and ciphertext without re-encrypting.
     */
    public BufferedImage cloakRaw(BufferedImage image, EnvelopeHeader header, byte[] ciphertext) {
        return cloakRaw(image, header, ciphertext, null);
    }

    public BufferedImage cloakRaw(BufferedImage image, EnvelopeHeader header, byte[] ciphertext, byte[] scatterKey) {
        int width = image.getWidth();
        int height = image.getHeight();
        int totalPixels = width * height;
        if (totalPixels < SequentialLayout.HEADER_PIXELS) {
            throw new InsufficientCarrierCapacityException(ciphertext.length, 0L);
        }

        int[] directPixels = RasterAccess.directPixels(image);
        BufferedImage carrier = (directPixels != null) ? image : RasterAccess.toCanonicalImage(image);
        int[] pixelBuffer = (directPixels != null) ? directPixels : RasterAccess.directPixels(carrier);

        boolean isScattered = (header.getFlags() & EnvelopeHeader.FLAG_SCATTERED) != 0;
        boolean isAdaptive = (header.getFlags() & EnvelopeHeader.FLAG_ADAPTIVE) != 0;

        EmbeddingLayout positionSource = isScattered
                ? new ScatteredLayout(totalPixels, scatterKey != null ? scatterKey : new byte[32])
                : new SequentialLayout(totalPixels);

        EmbeddingLayout layout;
        if (isAdaptive) {
            DepthProfile profile = DepthProfile.fromId(header.getProfileId());
            int[] mask = SobelEdgeDetector.computeMask(pixelBuffer, width, height, profile);
            layout = new AdaptiveLayout(positionSource, mask, width, height);
        } else {
            layout = positionSource;
        }

        long capacityBits = layout.capacityBits();
        long availableCapacityBytes = capacityBits / 8L;
        long requiredBits = (long) ciphertext.length * 8L;

        if (requiredBits > capacityBits) {
            throw new InsufficientCarrierCapacityException(ciphertext.length, availableCapacityBytes);
        }

        byte[] serializedHeader = header.serialize();
        embedHeader(pixelBuffer, serializedHeader);
        embedPayload(pixelBuffer, ciphertext, layout);

        return carrier;
    }

    /**
     * Extracts raw header and ciphertext without decrypting.
     */
    public record RawExtracted(EnvelopeHeader header, byte[] ciphertext) {}

    public RawExtracted revealRaw(BufferedImage image) {
        return revealRaw(image, null);
    }

    public RawExtracted revealRaw(BufferedImage image, byte[] scatterKey) {
        int width = image.getWidth();
        int height = image.getHeight();
        int totalPixels = width * height;
        if (totalPixels < SequentialLayout.HEADER_PIXELS) {
            throw new NoNyxPayloadException("Carrier image too small to contain a Nyx header");
        }

        int[] directPixels = RasterAccess.directPixels(image);
        BufferedImage carrier = (directPixels != null) ? image : RasterAccess.toCanonicalImage(image);
        int[] pixelBuffer = (directPixels != null) ? directPixels : RasterAccess.directPixels(carrier);

        byte[] rawHeader = extractHeader(pixelBuffer);
        long maxTheoreticalCapacityBytes = ((long) (totalPixels - SequentialLayout.HEADER_PIXELS) * 3L * (long) SobelEdgeDetector.MAX_DEPTH) / 8L;
        EnvelopeHeader header = EnvelopeHeader.deserialize(rawHeader, maxTheoreticalCapacityBytes);

        boolean isScattered = (header.getFlags() & EnvelopeHeader.FLAG_SCATTERED) != 0;
        boolean isAdaptive = (header.getFlags() & EnvelopeHeader.FLAG_ADAPTIVE) != 0;

        int[] mask = null;
        long realCapacityBits;
        if (isAdaptive) {
            DepthProfile profile = DepthProfile.fromId(header.getProfileId());
            mask = SobelEdgeDetector.computeMask(pixelBuffer, width, height, profile);
            long totalCap = 0L;
            for (int p = SequentialLayout.HEADER_PIXELS; p < totalPixels; p++) {
                totalCap += 3L * (long) mask[p];
            }
            realCapacityBits = totalCap;
        } else {
            realCapacityBits = (long) (totalPixels - SequentialLayout.HEADER_PIXELS) * 3L;
        }

        if (header.getCiphertextLength() * 8L > realCapacityBits) {
            throw new InvalidHeaderException(String.format(
                    "Ciphertext length %d bytes exceeds real carrier capacity of %d bytes for active layout",
                    header.getCiphertextLength(), realCapacityBits / 8L));
        }

        EmbeddingLayout positionSource = isScattered
                ? new ScatteredLayout(totalPixels, scatterKey != null ? scatterKey : new byte[32])
                : new SequentialLayout(totalPixels);

        EmbeddingLayout layout = isAdaptive
                ? new AdaptiveLayout(positionSource, mask, width, height)
                : positionSource;

        byte[] ciphertext = extractPayload(pixelBuffer, header.getCiphertextLength(), layout);

        return new RawExtracted(header, ciphertext);
    }

    private void embedHeader(int[] pixelBuffer, byte[] rawHeader) {
        int[] shifts = {16, 8, 0};
        int headerBitIndex = 0;
        int totalHeaderBits = EnvelopeHeader.HEADER_SIZE_BYTES * 8; // 400 bits

        for (int p = 0; p < SequentialLayout.HEADER_PIXELS; p++) {
            int pixel = pixelBuffer[p];
            for (int shift : shifts) {
                if (headerBitIndex >= totalHeaderBits) {
                    break;
                }
                int byteIdx = headerBitIndex / 8;
                int bitInByte = headerBitIndex % 8;
                int bit = (rawHeader[byteIdx] >> (7 - bitInByte)) & 1;

                pixel = (pixel & ~(1 << shift)) | (bit << shift);
                headerBitIndex++;
            }
            pixelBuffer[p] = pixel;
            if (headerBitIndex >= totalHeaderBits) {
                break;
            }
        }
    }

    private byte[] extractHeader(int[] pixelBuffer) {
        byte[] rawHeader = new byte[EnvelopeHeader.HEADER_SIZE_BYTES];
        int[] shifts = {16, 8, 0};
        int headerBitIndex = 0;
        int totalHeaderBits = EnvelopeHeader.HEADER_SIZE_BYTES * 8; // 400 bits

        for (int p = 0; p < SequentialLayout.HEADER_PIXELS; p++) {
            int pixel = pixelBuffer[p];
            for (int shift : shifts) {
                if (headerBitIndex >= totalHeaderBits) {
                    break;
                }
                int bit = (pixel >>> shift) & 1;
                int byteIdx = headerBitIndex / 8;
                int bitInByte = headerBitIndex % 8;

                rawHeader[byteIdx] |= (byte) (bit << (7 - bitInByte));
                headerBitIndex++;
            }
            if (headerBitIndex >= totalHeaderBits) {
                break;
            }
        }
        return rawHeader;
    }

    void embedPayload(int[] pixelBuffer, byte[] ciphertext, EmbeddingLayout layout) {
        int[] shifts = {16, 8, 0};
        long totalBits = (long) ciphertext.length * 8L;
        long bitsWritten = 0;

        while (bitsWritten < totalBits) {
            int pixelIdx = layout.nextPixelIndex();
            if (pixelIdx == -1) {
                throw new IllegalStateException("Pixel pool exhausted before embedding all payload bits");
            }
            int d = layout.depthAt(pixelIdx);
            if (d == 0) {
                continue;
            }
            int pixel = pixelBuffer[pixelIdx];

            for (int shift : shifts) {
                if (bitsWritten >= totalBits) {
                    break;
                }
                int c = (int) Math.min((long) d, totalBits - bitsWritten);
                // Read c bits from ciphertext MSB-first
                int val = 0;
                for (int b = 0; b < c; b++) {
                    long bitOffset = bitsWritten + b;
                    int byteIdx = (int) (bitOffset / 8);
                    int bitInByte = (int) (bitOffset % 8);
                    int bit = (ciphertext[byteIdx] >>> (7 - bitInByte)) & 1;
                    val = (val << 1) | bit;
                }

                int mask = ~(((1 << c) - 1) << shift);
                pixel = (pixel & mask) | (val << shift);
                bitsWritten += c;
            }
            pixelBuffer[pixelIdx] = pixel;
        }
    }

    byte[] extractPayload(int[] pixelBuffer, long ciphertextLength, EmbeddingLayout layout) {
        int[] shifts = {16, 8, 0};
        long totalBits = ciphertextLength * 8L;
        byte[] ciphertext = new byte[(int) ciphertextLength];
        long bitsRead = 0;

        while (bitsRead < totalBits) {
            int pixelIdx = layout.nextPixelIndex();
            if (pixelIdx == -1) {
                throw new IllegalStateException("Pixel pool exhausted before extracting all payload bits");
            }
            int d = layout.depthAt(pixelIdx);
            if (d == 0) {
                continue;
            }
            int pixel = pixelBuffer[pixelIdx];

            for (int shift : shifts) {
                if (bitsRead >= totalBits) {
                    break;
                }
                int c = (int) Math.min((long) d, totalBits - bitsRead);
                int val = (pixel >>> shift) & ((1 << c) - 1);

                // Write c bits to ciphertext MSB-first
                for (int b = 0; b < c; b++) {
                    long bitOffset = bitsRead + b;
                    int byteIdx = (int) (bitOffset / 8);
                    int bitInByte = (int) (bitOffset % 8);
                    int bit = (val >> (c - 1 - b)) & 1;
                    ciphertext[byteIdx] |= (byte) (bit << (7 - bitInByte));
                }
                bitsRead += c;
            }
        }
        return ciphertext;
    }
}
