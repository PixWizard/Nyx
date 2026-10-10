package org.ghost.core;

import org.ghost.crypto.AuthenticatedCipher;
import org.ghost.crypto.AuthenticationFailedException;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class AdaptiveLayoutTest {

    private BufferedImage roundTripPng(BufferedImage image) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        boolean written = ImageIO.write(image, "png", baos);
        if (!written) {
            throw new IllegalStateException("Failed to write PNG image");
        }
        return ImageIO.read(new ByteArrayInputStream(baos.toByteArray()));
    }

    private BufferedImage createPatternImage(int width, int height, long seed) {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Random rng = new Random(seed);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                // Generate varied pixel values to create diverse gradients
                int r = (x * 7 + y * 13 + rng.nextInt(64)) & 0xFF;
                int g = (x * 11 + y * 5 + rng.nextInt(64)) & 0xFF;
                int b = (x * 3 + y * 17 + rng.nextInt(64)) & 0xFF;
                int rgb = (0xFF << 24) | (r << 16) | (g << 8) | b;
                img.setRGB(x, y, rgb);
            }
        }
        return img;
    }

    @Test
    void printCapacityBenchmark() {
        int width = 100;
        int height = 100;
        int totalPixels = width * height; // 10,000 pixels

        // Flat image (uniform color RGB 128, 128, 128)
        BufferedImage flatImg = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D gFlat = flatImg.createGraphics();
        gFlat.setColor(new Color(128, 128, 128, 255));
        gFlat.fillRect(0, 0, width, height);
        gFlat.dispose();

        // Gradient image (smooth linear gradient from 0 to 255 across width)
        BufferedImage gradientImg = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int c = (x * 255) / (width - 1);
                int rgb = (0xFF << 24) | (c << 16) | (c << 8) | c;
                gradientImg.setRGB(x, y, rgb);
            }
        }

        // Random noise image (white noise uniformly distributed 0..255)
        BufferedImage noiseImg = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Random rng = new Random(123456L);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int r = rng.nextInt(256);
                int g = rng.nextInt(256);
                int b = rng.nextInt(256);
                int rgb = (0xFF << 24) | (r << 16) | (g << 8) | b;
                noiseImg.setRGB(x, y, rgb);
            }
        }

        BufferedImage[] images = {flatImg, gradientImg, noiseImg};
        String[] imageNames = {"Flat Image (100x100)", "Gradient Image (100x100)", "Random Noise (100x100)"};

        SteganoEngine engine = new SteganoEngine();
        System.out.println("\n=== MEASURED CARRIER CAPACITY BENCHMARK (100x100 images, 10,000 pixels) ===");
        System.out.printf("%-28s | %-16s | %-16s | %-16s | %-16s%n",
                "Carrier Image",
                "Seq / Non-Adapt",
                "Scat / Non-Adapt",
                "Seq / Adaptive",
                "Scat / Adaptive");
        System.out.println("------------------------------------------------------------------------------------------------------");

        for (int i = 0; i < images.length; i++) {
            BufferedImage img = images[i];
            int[] px = getPixelsCopy(img);

            // Constant depth 1: (totalPixels - 134) * 3 bits
            long nonAdaptiveBits = (long) (totalPixels - SequentialLayout.HEADER_PIXELS) * 3L;
            long nonAdaptiveBytes = nonAdaptiveBits / 8L;

            // Adaptive depth: sum over pool pixels of 3 * depth
            int[] mask = SobelEdgeDetector.computeMask(px, width, height, DepthProfile.DEFAULT);
            long adaptiveBits = 0L;
            for (int p = SequentialLayout.HEADER_PIXELS; p < totalPixels; p++) {
                adaptiveBits += 3L * (long) mask[p];
            }
            long adaptiveBytes = adaptiveBits / 8L;

            System.out.printf("%-28s | %13d B | %13d B | %13d B | %13d B%n",
                    imageNames[i],
                    nonAdaptiveBytes,
                    nonAdaptiveBytes,
                    adaptiveBytes,
                    adaptiveBytes);
        }
        System.out.println("======================================================================================================\n");
    }

    @Test
    void testBitwiseEmbedExtract() throws Exception {
        SteganoEngine engine = new SteganoEngine();
        int[] buf = new int[500];
        Random r = new Random(1234L);
        for (int i = 0; i < buf.length; i++) buf[i] = r.nextInt();

        byte[] payload = new byte[30];
        r.nextBytes(payload);

        SequentialLayout layoutEmbed = new SequentialLayout(500);
        SequentialLayout layoutExtract = new SequentialLayout(500);

        EnvelopeHeader header = new EnvelopeHeader(
                EnvelopeHeader.CIPHER_ID_AES_256_GCM,
                EnvelopeHeader.KDF_ID_ARGON2ID,
                (byte) 0,
                (byte) 0,
                8192,
                1,
                1,
                new byte[16],
                new byte[12],
                payload.length
        );

        BufferedImage img = new BufferedImage(25, 20, BufferedImage.TYPE_INT_ARGB);
        img.setRGB(0, 0, 25, 20, buf, 0, 25);

        engine.cloakRaw(img, header, payload);
        BufferedImage roundTripped = roundTripPng(img);
        SteganoEngine.RawExtracted extracted = engine.revealRaw(roundTripped);

        assertArrayEquals(payload, extracted.ciphertext(), "Raw extracted ciphertext must match embedded payload");
    }

    /**
     * Sobel unit test on a tiny image whose luma is strictly controlled.
     * Hand-computes the expected G for the center pixel, uniform image, and clamped border corners.
     */
    @Test
    void testSobelUnitMathOnControlledLuma() {
        // Construct a 3x3 image with a vertical step edge:
        // Column 0: RGB(0, 0, 0)       -> quantized (0, 0, 0)       -> luma = 0
        // Column 1: RGB(0, 0, 0)       -> quantized (0, 0, 0)       -> luma = 0
        // Column 2: RGB(255, 255, 255) -> quantized (248, 248, 248) -> luma = (299*248 + 587*248 + 114*248)/1000 = 248
        //
        // 3x3 Luma Grid:
        // [  0,   0, 248 ]
        // [  0,   0, 248 ]
        // [  0,   0, 248 ]
        //
        // Sobel Gx Kernel:
        // [ -1,  0, +1 ]
        // [ -2,  0, +2 ]
        // [ -1,  0, +1 ]
        //
        // Center pixel (1, 1):
        // Gx = (-1*0 + 1*248) + 2*(-1*0 + 1*248) + (-1*0 + 1*248)
        //    = 248 + 2*248 + 248 = 4 * 248 = 992
        // Gy = 0 (horizontal symmetry)
        // G = |992| + |0| = 992
        //
        // DepthProfile.DEFAULT thresholds: T1 = 16, T2 = 48, T3 = 112
        // Since G = 992 >= 112, depth = 3.
        int width = 3;
        int height = 3;
        int[] pixelBuffer = new int[9];
        int black = (0xFF << 24) | 0x000000;
        int white = (0xFF << 24) | 0xFFFFFF;

        // Set columns 0 and 1 to black, column 2 to white
        for (int y = 0; y < 3; y++) {
            pixelBuffer[y * 3 + 0] = black;
            pixelBuffer[y * 3 + 1] = black;
            pixelBuffer[y * 3 + 2] = white;
        }

        int[] mask = SobelEdgeDetector.computeMask(pixelBuffer, width, height, DepthProfile.DEFAULT);

        // Center pixel (1, 1) has index 1*3 + 1 = 4
        // Verify G = 992 and depth = 3
        assertEquals(3, mask[4], "Center pixel depth must be 3 for steep vertical step edge");

        // Now test corner pixel (0, 0) with border clamping:
        // For (0, 0): clamped x in {0, 0, 1}, clamped y in {0, 0, 1}.
        // All sampled coordinates are in column 0 or 1, where luma is identically 0.
        // Therefore Gx = 0, Gy = 0, G = 0 -> depth = 0 (since 0 < T1 = 16).
        assertEquals(0, mask[0], "Top-left corner (0,0) clamped neighborhood is all zeros -> depth 0");

        // Corner pixel (2, 0):
        // Clamped x: x-1=1, x=2, x+1 clamped to 2 -> {1, 2, 2}
        // Clamped y: y-1 clamped to 0, y=0, y+1=1 -> {0, 0, 1}
        // Sampled luma:
        // Row 0 (y=0): col 1 -> 0, col 2 -> 248, col 2 -> 248
        // Row 1 (y=0): col 1 -> 0, col 2 -> 248, col 2 -> 248
        // Row 2 (y=1): col 1 -> 0, col 2 -> 248, col 2 -> 248
        // Gx = (-1*0 + 1*248) + 2*(-1*0 + 1*248) + (-1*0 + 1*248) = 4 * 248 = 992
        // Gy = (-1*0 - 2*248 - 1*248) + (1*0 + 2*248 + 1*248) = -744 + 744 = 0
        // G = 992 -> depth = 3
        assertEquals(3, mask[2], "Top-right corner (2,0) has Gx = 992 -> depth 3");

        // Uniform image test:
        // Every pixel RGB(100, 150, 200).
        // R' = 100 & 0xF8 = 96, G' = 150 & 0xF8 = 144, B' = 200 & 0xF8 = 200.
        // Luma = (299*96 + 587*144 + 114*200) / 1000 = 136.
        // Constant luma everywhere -> Gx = 0, Gy = 0, G = 0 everywhere -> depth = 0 everywhere.
        int[] uniformBuffer = new int[9];
        int uniformRgb = (0xFF << 24) | (100 << 16) | (150 << 8) | 200;
        Arrays.fill(uniformBuffer, uniformRgb);

        int[] uniformMask = SobelEdgeDetector.computeMask(uniformBuffer, width, height, DepthProfile.DEFAULT);
        for (int i = 0; i < 9; i++) {
            assertEquals(0, uniformMask[i], "Uniform image must have depth 0 everywhere");
        }
    }

    /**
     * THE CORE INVARIANCE PROPERTY TEST:
     * For random images of various dimensions and random payloads,
     * mask(cover) == mask(stego) after embedding at maximum possible depth.
     * The mask computed from the stego image MUST be bit-for-bit identical to that of the cover image.
     */
    @Test
    void testInvariancePropertyAcrossRandomImagesAndPayloads() {
        SteganoEngine engine = new SteganoEngine();
        int[][] dimensions = {
                {40, 40},
                {64, 50},
                {100, 80}
        };

        long[] seeds = {42L, 1337L, 99999L};
        char[] password = "InvariancePassword123!".toCharArray();

        for (int i = 0; i < dimensions.length; i++) {
            int width = dimensions[i][0];
            int height = dimensions[i][1];
            long seed = seeds[i];

            BufferedImage cover = createPatternImage(width, height, seed);
            int[] coverBuffer = getPixelsCopy(cover);
            int[] coverMask = SobelEdgeDetector.computeMask(coverBuffer, width, height, DepthProfile.DEFAULT);

            // Compute capacity for this cover image
            long poolBits = 0L;
            for (int p = SequentialLayout.HEADER_PIXELS; p < width * height; p++) {
                poolBits += 3L * (long) coverMask[p];
            }
            long maxBytes = poolBits / 8L;
            // Subtract overhead: 1 byte extLen + 3 bytes ext ("dat") + 16 bytes GCM tag
            int maxPayloadBytes = (int) Math.max(0, maxBytes - 20L);

            // Test embedding with different payload sizes: small, half, and maximum
            int[] payloadSizes = {16, maxPayloadBytes / 2, maxPayloadBytes};
            for (int size : payloadSizes) {
                if (size <= 0) continue;
                byte[] payload = new byte[size];
                new Random(seed + size).nextBytes(payload);

                // Clone cover image
                BufferedImage carrier = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
                Graphics2D g = carrier.createGraphics();
                g.drawImage(cover, 0, 0, null);
                g.dispose();

                // Cloak with adaptive = true
                engine.cloak(carrier, payload, "dat", password, true, true);

                // Extract stego pixel buffer and recompute mask
                int[] stegoBuffer = getPixelsCopy(carrier);
                int[] stegoMask = SobelEdgeDetector.computeMask(stegoBuffer, width, height, DepthProfile.DEFAULT);

                // INVARIANCE ASSERTION: Cover mask and Stego mask must be IDENTICAL
                assertArrayEquals(coverMask, stegoMask,
                        String.format("Stego mask must identically match cover mask for %dx%d with payload %d bytes",
                                width, height, size));
            }
        }
    }

    /**
     * Round trips through real PNG ImageIO for all four flag combinations:
     * (scatter x adaptive) with payloads: 0 bytes, 10 KB, and exact capacity.
     */
    @Test
    void testPngRoundTripsAllFlagCombinations() throws Exception {
        SteganoEngine engine = new SteganoEngine();
        char[] password = "RoundTripSecretKey2026!".toCharArray();

        // 200x200 image has 40,000 pixels.
        // Pool has 39,866 pixels. With pattern image, capacity exceeds 20 KB.
        int width = 200;
        int height = 200;

        boolean[] scatterOptions = {false, true};
        boolean[] adaptiveOptions = {false, true};

        for (boolean scatter : scatterOptions) {
            for (boolean adaptive : adaptiveOptions) {
                BufferedImage baseCarrier = createPatternImage(width, height, 8888L);
                int[] pixelBuffer = getPixelsCopy(baseCarrier);

                long capacityBits;
                if (adaptive) {
                    int[] mask = SobelEdgeDetector.computeMask(pixelBuffer, width, height, DepthProfile.DEFAULT);
                    long totalCap = 0L;
                    for (int p = SequentialLayout.HEADER_PIXELS; p < width * height; p++) {
                        totalCap += 3L * (long) mask[p];
                    }
                    capacityBits = totalCap;
                } else {
                    capacityBits = (long) (width * height - SequentialLayout.HEADER_PIXELS) * 3L;
                }

                // Envelope overhead: 1 byte extLen + 3 bytes ext ("bin") + 16 bytes GCM tag
                int overheadBytes = 1 + 3 + AuthenticatedCipher.GCM_TAG_LENGTH_BYTES;
                int exactCapacityPayloadBytes = (int) (capacityBits / 8L) - overheadBytes;
                assertTrue(exactCapacityPayloadBytes > 10240, "Carrier must accommodate at least 10 KB");

                int[] testPayloadSizes = {0, 10240, exactCapacityPayloadBytes};

                for (int payloadSize : testPayloadSizes) {
                    byte[] payload = new byte[payloadSize];
                    new Random(payloadSize + 12345).nextBytes(payload);

                    // Fresh copy of carrier image
                    BufferedImage carrier = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
                    Graphics2D g = carrier.createGraphics();
                    g.drawImage(baseCarrier, 0, 0, null);
                    g.dispose();

                    // Cloak payload
                    engine.cloak(carrier, payload, "bin", password.clone(), scatter, adaptive);

                    // PNG encode/decode round trip
                    BufferedImage ghost = roundTripPng(carrier);

                    // Reveal and verify
                    PayloadEnvelope.Unpacked unpacked = engine.reveal(ghost, password.clone());
                    assertEquals("bin", unpacked.extension());
                    assertArrayEquals(payload, unpacked.fileBytes(),
                            String.format("Round-trip failed for scatter=%b, adaptive=%b, size=%d bytes",
                                    scatter, adaptive, payloadSize));

                    // Verify wrong password fails authentication
                    char[] wrongPassword = "WrongPassword999!".toCharArray();
                    assertThrows(AuthenticationFailedException.class, () -> engine.reveal(ghost, wrongPassword));
                }
            }
        }
    }

    /**
     * Capacity tests:
     * 1. Flat single-color image capacity is far smaller than noisy random image.
     * 2. Exceeding capacity by 1 byte throws InsufficientCarrierCapacityException with pixel buffer untouched.
     */
    @Test
    void testCapacityPropertiesAndBoundaryEnforcement() {
        SteganoEngine engine = new SteganoEngine();
        int width = 80;
        int height = 80;

        // Flat single-color image: luma is uniform, G = 0 everywhere -> all pool depths are 0
        BufferedImage flatImage = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D gFlat = flatImage.createGraphics();
        gFlat.setColor(new Color(120, 120, 120, 255));
        gFlat.fillRect(0, 0, width, height);
        gFlat.dispose();

        int[] flatBuffer = getPixelsCopy(flatImage);
        int[] flatMask = SobelEdgeDetector.computeMask(flatBuffer, width, height, DepthProfile.DEFAULT);
        long flatCapacityBits = 0L;
        for (int p = SequentialLayout.HEADER_PIXELS; p < width * height; p++) {
            flatCapacityBits += 3L * (long) flatMask[p];
        }
        assertEquals(0L, flatCapacityBits, "Flat image must have 0 adaptive capacity bits");

        // Noisy random image
        BufferedImage noisyImage = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Random rng = new Random(42L);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int r = rng.nextInt(256);
                int gr = rng.nextInt(256);
                int b = rng.nextInt(256);
                noisyImage.setRGB(x, y, (0xFF << 24) | (r << 16) | (gr << 8) | b);
            }
        }

        int[] noisyBuffer = getPixelsCopy(noisyImage);
        int[] noisyMask = SobelEdgeDetector.computeMask(noisyBuffer, width, height, DepthProfile.DEFAULT);
        long noisyCapacityBits = 0L;
        for (int p = SequentialLayout.HEADER_PIXELS; p < width * height; p++) {
            noisyCapacityBits += 3L * (long) noisyMask[p];
        }

        assertTrue(flatCapacityBits < noisyCapacityBits,
                "Adaptive capacity on flat image must be far smaller than on noisy image");
        assertTrue(noisyCapacityBits > 10000L, "Noisy image should have substantial capacity");

        // Exceed capacity by 1 byte on noisy image
        long maxPayloadBytes = (noisyCapacityBits / 8L) - (1 + 3 + AuthenticatedCipher.GCM_TAG_LENGTH_BYTES);
        assertTrue(maxPayloadBytes > 0);

        byte[] oversizedPayload = new byte[(int) maxPayloadBytes + 1];
        char[] password = "TestPassword!".toCharArray();

        int[] originalPixels = noisyImage.getRGB(0, 0, width, height, null, 0, width);

        assertThrows(InsufficientCarrierCapacityException.class, () ->
                engine.cloak(noisyImage, oversizedPayload, "bin", password, true, true));

        // Assert pixel buffer was untouched before exception was thrown
        int[] pixelsAfterFailure = noisyImage.getRGB(0, 0, width, height, null, 0, width);
        assertArrayEquals(originalPixels, pixelsAfterFailure,
                "Carrier pixel buffer must remain completely untouched when capacity is exceeded");
    }

    /**
     * Unknown profileId in a header with a re-fixed CRC throws UnsupportedFormatException.
     */
    @Test
    void testUnknownProfileIdWithReFixedCrcThrowsUnsupportedFormatException() {
        byte unknownProfileId = 0x42;
        byte flags = EnvelopeHeader.FLAG_ADAPTIVE;

        byte[] salt = new byte[16];
        byte[] nonce = new byte[12];
        new Random().nextBytes(salt);
        new Random().nextBytes(nonce);

        EnvelopeHeader header = new EnvelopeHeader(
                EnvelopeHeader.CIPHER_ID_AES_256_GCM,
                EnvelopeHeader.KDF_ID_ARGON2ID,
                flags,
                unknownProfileId,
                8192,
                1,
                1,
                salt,
                nonce,
                100L
        );

        byte[] serializedHeader = header.serialize();

        UnsupportedFormatException ex = assertThrows(UnsupportedFormatException.class, () ->
                EnvelopeHeader.deserialize(serializedHeader, 10000L));
        assertTrue(ex.getMessage().contains("profile id"));
    }

    /**
     * Per-pixel change bound:
     * - Every pool pixel: |new channel - old channel| < 2^depth(p) for R, G, B; alpha is unchanged.
     * - Header pixels (0..133): |new channel - old channel| <= 1 for R, G, B; alpha is unchanged.
     */
    @Test
    void testPerPixelChangeBound() {
        SteganoEngine engine = new SteganoEngine();
        int width = 60;
        int height = 60;
        BufferedImage carrier = createPatternImage(width, height, 777L);

        int[] oldPixels = getPixelsCopy(carrier);
        int[] mask = SobelEdgeDetector.computeMask(oldPixels, width, height, DepthProfile.DEFAULT);

        byte[] payload = new byte[256];
        new Random(123L).nextBytes(payload);
        char[] password = "BoundPassword123!".toCharArray();

        // Cloak with adaptive = true
        engine.cloak(carrier, payload, "txt", password, true, true);
        int[] newPixels = getPixelsCopy(carrier);

        int totalPixels = width * height;
        for (int p = 0; p < totalPixels; p++) {
            int oldPx = oldPixels[p];
            int newPx = newPixels[p];

            // Alpha must be unchanged
            int oldA = (oldPx >>> 24) & 0xFF;
            int newA = (newPx >>> 24) & 0xFF;
            assertEquals(oldA, newA, "Alpha must never be modified");

            int oldR = (oldPx >> 16) & 0xFF;
            int newR = (newPx >> 16) & 0xFF;
            int oldG = (oldPx >> 8) & 0xFF;
            int newG = (newPx >> 8) & 0xFF;
            int oldB = oldPx & 0xFF;
            int newB = newPx & 0xFF;

            int diffR = Math.abs(newR - oldR);
            int diffG = Math.abs(newG - oldG);
            int diffB = Math.abs(newB - oldB);

            if (p < SequentialLayout.HEADER_PIXELS) {
                // Header pixel: depth is 1
                assertTrue(diffR <= 1, "Header pixel R change must be <= 1");
                assertTrue(diffG <= 1, "Header pixel G change must be <= 1");
                assertTrue(diffB <= 1, "Header pixel B change must be <= 1");
            } else {
                // Pool pixel: bound is < 2^depth(p)
                int depth = mask[p];
                int maxAllowedExclusive = 1 << depth; // 2^depth
                assertTrue(diffR < maxAllowedExclusive,
                        String.format("Pixel %d R diff %d must be < 2^%d (%d)", p, diffR, depth, maxAllowedExclusive));
                assertTrue(diffG < maxAllowedExclusive,
                        String.format("Pixel %d G diff %d must be < 2^%d (%d)", p, diffG, depth, maxAllowedExclusive));
                assertTrue(diffB < maxAllowedExclusive,
                        String.format("Pixel %d B diff %d must be < 2^%d (%d)", p, diffB, depth, maxAllowedExclusive));
            }
        }
    }

    private static int[] getPixelsCopy(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        int[] pixelBuffer = new int[width * height];
        image.getRGB(0, 0, width, height, pixelBuffer, 0, width);
        return pixelBuffer;
    }

    @Test
    void testBitPackingSymmetryPartialChunksAndDepths() throws Exception {
        SteganoEngine engine = new SteganoEngine();
        Random rng = new Random(424242L);

        // Test with custom synthetic layouts of constant depth 1, 2, and 3
        // where totalBits % (depth * 3) yields partial channel usage and partial chunk bits.
        for (int depth = 1; depth <= 3; depth++) {
            final int fixedDepth = depth;
            for (int len = 1; len <= 40; len++) {
                byte[] payload = new byte[len];
                rng.nextBytes(payload);

                // Build a carrier large enough for header + payload
                int totalBits = len * 8;
                int payloadPixels = (totalBits + (fixedDepth * 3) - 1) / (fixedDepth * 3);
                int totalPixels = SequentialLayout.HEADER_PIXELS + payloadPixels + 20;
                int side = (int) Math.ceil(Math.sqrt(totalPixels));

                BufferedImage carrier = new BufferedImage(side, side, BufferedImage.TYPE_INT_ARGB);
                int[] pixelBuffer = RasterAccess.directPixels(carrier);
                for (int i = 0; i < pixelBuffer.length; i++) {
                    pixelBuffer[i] = 0xFF000000 | rng.nextInt(0x01000000);
                }

                // Create fixed-depth layout
                int[] mask = new int[side * side];
                Arrays.fill(mask, fixedDepth);
                SequentialLayout seqSource = new SequentialLayout(side * side);
                AdaptiveLayout layout = new AdaptiveLayout(seqSource, mask, side, side);

                // Embed payload directly via layout
                engine.embedPayload(pixelBuffer, payload, layout);

                // Extract with same layout
                SequentialLayout extractSource = new SequentialLayout(side * side);
                AdaptiveLayout extractLayout = new AdaptiveLayout(extractSource, mask, side, side);
                byte[] extracted = engine.extractPayload(pixelBuffer, payload.length, extractLayout);

                assertArrayEquals(payload, extracted,
                        String.format("Extracted payload must match for depth %d and length %d (final pixel bits: %d)",
                                fixedDepth, len, totalBits % (fixedDepth * 3)));
            }
        }
    }

    @Test
    void testCapacityEdgeCasesExhaustive() throws IOException {
        SteganoEngine engine = new SteganoEngine();
        char[] password = "CapacityEdgeCasePassword123!".toCharArray();

        // Image with at most HEADER_PIXELS pixels (134x1 = 134 pixels)
        BufferedImage img134 = new BufferedImage(134, 1, BufferedImage.TYPE_INT_ARGB);
        InsufficientCarrierCapacityException ex134 = assertThrows(
                InsufficientCarrierCapacityException.class,
                () -> engine.cloak(img134, new byte[]{0x42}, "", password.clone())
        );
        assertEquals(0L, ex134.getAvailableBytes());
        assertTrue(ex134.getRequiredBytes() > 0);

        // 1x1 image (1 pixel)
        BufferedImage img1x1 = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        InsufficientCarrierCapacityException ex1x1 = assertThrows(
                InsufficientCarrierCapacityException.class,
                () -> engine.cloak(img1x1, new byte[0], "", password.clone())
        );
        assertEquals(0L, ex1x1.getAvailableBytes());
        assertThrows(NoNyxPayloadException.class, () -> engine.reveal(img1x1, password.clone()));

        // Width of 1 (1x200) and Height of 1 (200x1)
        // 200 pixels: pool has 200 - 134 = 66 pixels. Depth 1 bits = 66 * 3 = 198 bits = 24 bytes.
        // Framing with empty ext is 17 bytes, so usable payload is 24 - 17 = 7 bytes.
        byte[] payload7 = new byte[]{1, 2, 3, 4, 5, 6, 7};

        // 200x1 (height 1)
        BufferedImage img200x1 = new BufferedImage(200, 1, BufferedImage.TYPE_INT_ARGB);
        int[] px200x1 = RasterAccess.directPixels(img200x1);
        for (int i = 0; i < 200; i++) px200x1[i] = 0xFF102030;
        engine.cloak(img200x1, payload7, "", password.clone(), false, false);
        BufferedImage restored200x1 = roundTripPng(img200x1);
        PayloadEnvelope.Unpacked unp200x1 = engine.reveal(restored200x1, password.clone());
        assertArrayEquals(payload7, unp200x1.fileBytes());

        // 1x200 (width 1)
        BufferedImage img1x200 = new BufferedImage(1, 200, BufferedImage.TYPE_INT_ARGB);
        int[] px1x200 = RasterAccess.directPixels(img1x200);
        for (int i = 0; i < 200; i++) px1x200[i] = 0xFF102030;
        engine.cloak(img1x200, payload7, "", password.clone(), false, false);
        BufferedImage restored1x200 = roundTripPng(img1x200);
        PayloadEnvelope.Unpacked unp1x200 = engine.reveal(restored1x200, password.clone());
        assertArrayEquals(payload7, unp1x200.fileBytes());

        // Adaptive mask that is entirely depth 0 (flat uniform image)
        BufferedImage flatImg = new BufferedImage(100, 100, BufferedImage.TYPE_INT_ARGB);
        int[] flatPx = RasterAccess.directPixels(flatImg);
        Arrays.fill(flatPx, 0xFF808080); // Sobel G = 0 everywhere -> Depth 0 everywhere
        InsufficientCarrierCapacityException exFlat = assertThrows(
                InsufficientCarrierCapacityException.class,
                () -> engine.cloak(flatImg, new byte[0], "", password.clone(), true, true)
        );
        assertEquals(0L, exFlat.getAvailableBytes());

        // Exact capacity and capacity + 1 (30x30 carrier)
        int width = 30;
        int height = 30; // 900 pixels
        long poolPixels = (width * height) - SequentialLayout.HEADER_PIXELS; // 766
        long capacityBytes = (poolPixels * 3L) / 8L; // 287 bytes
        int exactPayloadLen = (int) (capacityBytes - 17L); // 270 bytes

        byte[] exactPayload = new byte[exactPayloadLen];
        new Random(444L).nextBytes(exactPayload);

        BufferedImage imgExact = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        int[] pxExact = RasterAccess.directPixels(imgExact);
        for (int i = 0; i < pxExact.length; i++) pxExact[i] = 0xFF001122;
        engine.cloak(imgExact, exactPayload, "", password.clone(), false, false);
        BufferedImage restoredExact = roundTripPng(imgExact);
        PayloadEnvelope.Unpacked unpExact = engine.reveal(restoredExact, password.clone());
        assertArrayEquals(exactPayload, unpExact.fileBytes());

        // Capacity + 1
        byte[] overflowPayload = new byte[exactPayloadLen + 1];
        BufferedImage imgOverflow = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        int[] pxOverflow = RasterAccess.directPixels(imgOverflow);
        for (int i = 0; i < pxOverflow.length; i++) pxOverflow[i] = 0xFF001122;
        InsufficientCarrierCapacityException exOver = assertThrows(
                InsufficientCarrierCapacityException.class,
                () -> engine.cloak(imgOverflow, overflowPayload, "", password.clone(), false, false)
        );
        assertEquals(capacityBytes, exOver.getAvailableBytes());
        assertEquals(capacityBytes + 1L, exOver.getRequiredBytes());
    }
}
