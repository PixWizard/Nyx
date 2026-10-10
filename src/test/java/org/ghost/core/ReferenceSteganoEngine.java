package org.ghost.core;

import org.ghost.crypto.AuthenticatedCipher;
import org.ghost.crypto.NyxKeyDerivation;

import java.awt.image.BufferedImage;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * Reference implementation of SteganoEngine using the legacy bulk getRGB / setRGB copy path.
 * Kept exclusively in src/test for bit-for-bit equivalence testing and performance benchmarking.
 */
public class ReferenceSteganoEngine {

    private final SecureRandom secureRandom = new SecureRandom();

    public void cloak(BufferedImage image, byte[] fileBytes, String extension, char[] password) {
        cloak(image, fileBytes, extension, password, true, false);
    }

    public void cloak(BufferedImage image, byte[] fileBytes, String extension, char[] password, boolean scatter) {
        cloak(image, fileBytes, extension, password, scatter, false);
    }

    public void cloak(BufferedImage image, byte[] fileBytes, String extension, char[] password,
                      boolean scatter, boolean adaptive) {
        byte[] salt = new byte[NyxKeyDerivation.SALT_LENGTH_BYTES];
        byte[] nonce = new byte[NyxKeyDerivation.NONCE_LENGTH_BYTES];
        secureRandom.nextBytes(salt);
        secureRandom.nextBytes(nonce);
        cloakWithFixedCrypto(image, fileBytes, extension, password, scatter, adaptive, salt, nonce);
    }

    public void cloakWithFixedCrypto(BufferedImage image, byte[] fileBytes, String extension, char[] password,
                                     boolean scatter, boolean adaptive, byte[] salt, byte[] nonce) {
        if (image == null) {
            throw new IllegalArgumentException("Carrier image cannot be null");
        }

        byte[] plaintext = PayloadEnvelope.pack(fileBytes, extension);

        int memoryKiB = NyxKeyDerivation.DEFAULT_ARGON2_MEMORY_KIB;
        int iterations = NyxKeyDerivation.DEFAULT_ARGON2_ITERATIONS;
        int parallelism = NyxKeyDerivation.DEFAULT_ARGON2_PARALLELISM;

        long ciphertextLength = (long) plaintext.length + AuthenticatedCipher.GCM_TAG_LENGTH_BYTES;

        int width = image.getWidth();
        int height = image.getHeight();
        int totalPixels = width * height;
        if (totalPixels < SequentialLayout.HEADER_PIXELS) {
            throw new InsufficientCarrierCapacityException(ciphertextLength, 0L);
        }

        // REFERENCE PATH: Bulk getRGB copy into newly allocated int[] buffer
        int[] pixelBuffer = new int[totalPixels];
        image.getRGB(0, 0, width, height, pixelBuffer, 0, width);

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

        byte[] ciphertext;
        byte[] scatterKey;
        try (NyxKeyDerivation.DerivedKeys keys = NyxKeyDerivation.deriveKeys(password, salt, memoryKiB, iterations, parallelism)) {
            ciphertext = AuthenticatedCipher.encrypt(plaintext, keys.aesKey(), nonce, aad);
            scatterKey = Arrays.copyOf(keys.scatterKey(), keys.scatterKey().length);
        }

        try {
            EmbeddingLayout positionSource = scatter
                    ? new ScatteredLayout(totalPixels, scatterKey)
                    : new SequentialLayout(totalPixels);

            EmbeddingLayout layout = adaptive
                    ? new AdaptiveLayout(positionSource, mask, width, height)
                    : positionSource;

            embedHeader(pixelBuffer, serializedHeader);
            embedPayload(pixelBuffer, ciphertext, layout);

            // REFERENCE PATH: Bulk setRGB write-back into BufferedImage
            image.setRGB(0, 0, width, height, pixelBuffer, 0, width);
        } finally {
            Arrays.fill(scatterKey, (byte) 0);
        }
    }

    public PayloadEnvelope.Unpacked reveal(BufferedImage image, char[] password) {
        if (image == null) {
            throw new IllegalArgumentException("Carrier image cannot be null");
        }

        int width = image.getWidth();
        int height = image.getHeight();
        int totalPixels = width * height;
        if (totalPixels < SequentialLayout.HEADER_PIXELS) {
            throw new NoNyxPayloadException("Carrier image too small to contain a Nyx header");
        }

        // REFERENCE PATH: Bulk getRGB copy into newly allocated int[] buffer
        int[] pixelBuffer = new int[totalPixels];
        image.getRGB(0, 0, width, height, pixelBuffer, 0, width);

        byte[] rawHeader = extractHeader(pixelBuffer);

        long maxTheoreticalCapacityBytes = ((long) (totalPixels - SequentialLayout.HEADER_PIXELS) * 3L * (long) SobelEdgeDetector.MAX_DEPTH) / 8L;
        EnvelopeHeader header = EnvelopeHeader.deserialize(rawHeader, maxTheoreticalCapacityBytes);

        byte[] serializedHeader = header.serialize();
        byte[] aad = Arrays.copyOfRange(serializedHeader, 0, EnvelopeHeader.AAD_SIZE_BYTES);

        byte[] plaintext;
        try (NyxKeyDerivation.DerivedKeys keys = NyxKeyDerivation.deriveKeys(
                password, header.getSalt(), header.getArgon2MemoryKiB(),
                header.getArgon2Iterations(), header.getArgon2Parallelism())) {

            boolean isScattered = (header.getFlags() & EnvelopeHeader.FLAG_SCATTERED) != 0;
            boolean isAdaptive = (header.getFlags() & EnvelopeHeader.FLAG_ADAPTIVE) != 0;

            EmbeddingLayout positionSource = isScattered
                    ? new ScatteredLayout(totalPixels, keys.scatterKey())
                    : new SequentialLayout(totalPixels);

            EmbeddingLayout layout;
            if (isAdaptive) {
                DepthProfile profile = DepthProfile.fromId(header.getProfileId());
                int[] mask = SobelEdgeDetector.computeMask(pixelBuffer, width, height, profile);
                layout = new AdaptiveLayout(positionSource, mask, width, height);
            } else {
                layout = positionSource;
            }

            byte[] ciphertext = extractPayload(pixelBuffer, header.getCiphertextLength(), layout);
            plaintext = AuthenticatedCipher.decrypt(ciphertext, keys.aesKey(), header.getNonce(), aad);
        }

        return PayloadEnvelope.unpack(plaintext);
    }

    public void cloakRaw(BufferedImage image, EnvelopeHeader header, byte[] ciphertext, byte[] scatterKey) {
        int width = image.getWidth();
        int height = image.getHeight();
        int totalPixels = width * height;
        if (totalPixels < SequentialLayout.HEADER_PIXELS) {
            throw new InsufficientCarrierCapacityException(ciphertext.length, 0L);
        }

        int[] pixelBuffer = new int[totalPixels];
        image.getRGB(0, 0, width, height, pixelBuffer, 0, width);

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

        image.setRGB(0, 0, width, height, pixelBuffer, 0, width);
    }

    public SteganoEngine.RawExtracted revealRaw(BufferedImage image, byte[] scatterKey) {
        int width = image.getWidth();
        int height = image.getHeight();
        int totalPixels = width * height;
        if (totalPixels < SequentialLayout.HEADER_PIXELS) {
            throw new NoNyxPayloadException("Carrier image too small to contain a Nyx header");
        }

        int[] pixelBuffer = new int[totalPixels];
        image.getRGB(0, 0, width, height, pixelBuffer, 0, width);

        byte[] rawHeader = extractHeader(pixelBuffer);
        long maxTheoreticalCapacityBytes = ((long) (totalPixels - SequentialLayout.HEADER_PIXELS) * 3L * (long) SobelEdgeDetector.MAX_DEPTH) / 8L;
        EnvelopeHeader header = EnvelopeHeader.deserialize(rawHeader, maxTheoreticalCapacityBytes);

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

        byte[] ciphertext = extractPayload(pixelBuffer, header.getCiphertextLength(), layout);
        return new SteganoEngine.RawExtracted(header, ciphertext);
    }

    private void embedHeader(int[] pixelBuffer, byte[] rawHeader) {
        int[] shifts = {16, 8, 0};
        int headerBitIndex = 0;
        int totalHeaderBits = EnvelopeHeader.HEADER_SIZE_BYTES * 8;

        for (int p = 0; p < SequentialLayout.HEADER_PIXELS; p++) {
            int pixel = pixelBuffer[p];
            for (int shift : shifts) {
                if (headerBitIndex >= totalHeaderBits) break;
                int byteIdx = headerBitIndex / 8;
                int bitInByte = headerBitIndex % 8;
                int bit = (rawHeader[byteIdx] >> (7 - bitInByte)) & 1;

                pixel = (pixel & ~(1 << shift)) | (bit << shift);
                headerBitIndex++;
            }
            pixelBuffer[p] = pixel;
            if (headerBitIndex >= totalHeaderBits) break;
        }
    }

    private byte[] extractHeader(int[] pixelBuffer) {
        byte[] rawHeader = new byte[EnvelopeHeader.HEADER_SIZE_BYTES];
        int[] shifts = {16, 8, 0};
        int headerBitIndex = 0;
        int totalHeaderBits = EnvelopeHeader.HEADER_SIZE_BYTES * 8;

        for (int p = 0; p < SequentialLayout.HEADER_PIXELS; p++) {
            int pixel = pixelBuffer[p];
            for (int shift : shifts) {
                if (headerBitIndex >= totalHeaderBits) break;
                int bit = (pixel >> shift) & 1;
                int byteIdx = headerBitIndex / 8;
                int bitInByte = headerBitIndex % 8;

                rawHeader[byteIdx] |= (byte) (bit << (7 - bitInByte));
                headerBitIndex++;
            }
            if (headerBitIndex >= totalHeaderBits) break;
        }
        return rawHeader;
    }

    private void embedPayload(int[] pixelBuffer, byte[] ciphertext, EmbeddingLayout layout) {
        int[] shifts = {16, 8, 0};
        long totalBits = (long) ciphertext.length * 8L;
        long bitsWritten = 0;

        while (bitsWritten < totalBits) {
            int pixelIdx = layout.nextPixelIndex();
            if (pixelIdx == -1) {
                throw new IllegalStateException("Pixel pool exhausted before embedding all payload bits");
            }
            int d = layout.depthAt(pixelIdx);
            if (d == 0) continue;
            int pixel = pixelBuffer[pixelIdx];

            for (int shift : shifts) {
                if (bitsWritten >= totalBits) break;
                int c = (int) Math.min((long) d, totalBits - bitsWritten);
                int val = 0;
                for (int b = 0; b < c; b++) {
                    long bitOffset = bitsWritten + b;
                    int byteIdx = (int) (bitOffset / 8);
                    int bitInByte = (int) (bitOffset % 8);
                    int bit = (ciphertext[byteIdx] >> (7 - bitInByte)) & 1;
                    val = (val << 1) | bit;
                }

                int mask = ~(((1 << c) - 1) << shift);
                pixel = (pixel & mask) | (val << shift);
                bitsWritten += c;
            }
            pixelBuffer[pixelIdx] = pixel;
        }
    }

    private byte[] extractPayload(int[] pixelBuffer, long ciphertextLength, EmbeddingLayout layout) {
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
            if (d == 0) continue;
            int pixel = pixelBuffer[pixelIdx];

            for (int shift : shifts) {
                if (bitsRead >= totalBits) break;
                int c = (int) Math.min((long) d, totalBits - bitsRead);
                int val = (pixel >> shift) & ((1 << c) - 1);

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
