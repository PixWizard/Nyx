package org.ghost.core;

import org.ghost.crypto.AuthenticationFailedException;
import org.ghost.crypto.ChaCha20Stream;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ScatteredLayoutTest {

    private final SecureRandom random = new SecureRandom();

    private BufferedImage createCarrier(int width, int height) {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(110, 130, 160, 255));
        g.fillRect(0, 0, width, height);
        g.dispose();
        return img;
    }

    private BufferedImage roundTripPng(BufferedImage image) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        boolean written = ImageIO.write(image, "png", baos);
        if (!written) {
            throw new IllegalStateException("Failed to write PNG to ByteArrayOutputStream");
        }
        return ImageIO.read(new ByteArrayInputStream(baos.toByteArray()));
    }

    @Test
    void testPermutationIsBijectionOnFullRange() {
        int poolSize = 1000;
        int totalPixels = SequentialLayout.HEADER_PIXELS + poolSize; // 134 + 1000 = 1134
        byte[] key = new byte[32];
        random.nextBytes(key);

        ScatteredLayout layout = new ScatteredLayout(totalPixels, key);
        assertEquals(poolSize, layout.getPoolSize());

        BitSet seen = new BitSet(poolSize);
        for (int i = 0; i < poolSize; i++) {
            int pixelIndex = layout.nextPixelIndex();
            assertTrue(pixelIndex >= SequentialLayout.HEADER_PIXELS, "Pixel index must be in payload pool");
            assertTrue(pixelIndex < totalPixels, "Pixel index must not exceed total pixels");

            int offset = pixelIndex - SequentialLayout.HEADER_PIXELS;
            assertFalse(seen.get(offset), "Pixel index " + pixelIndex + " visited more than once (not a bijection)");
            seen.set(offset);
        }

        assertEquals(poolSize, seen.cardinality(), "All elements in pool must be visited exactly once");
        assertThrows(IllegalStateException.class, layout::nextPixelIndex, "Exhausted pool must throw IllegalStateException");
        layout.close();
    }

    @Test
    void testDeterminismSameAndDifferentKeys() {
        int totalPixels = 1000;
        byte[] key1 = new byte[32];
        random.nextBytes(key1);
        byte[] key2 = Arrays.copyOf(key1, key1.length);
        key2[0] ^= 0x01; // Ensure different key

        ScatteredLayout layout1a = new ScatteredLayout(totalPixels, key1);
        ScatteredLayout layout1b = new ScatteredLayout(totalPixels, key1);
        ScatteredLayout layout2 = new ScatteredLayout(totalPixels, key2);

        int[] seq1a = new int[32];
        int[] seq1b = new int[32];
        int[] seq2 = new int[32];

        for (int i = 0; i < 32; i++) {
            seq1a[i] = layout1a.nextPixelIndex();
            seq1b[i] = layout1b.nextPixelIndex();
            seq2[i] = layout2.nextPixelIndex();
        }

        assertArrayEquals(seq1a, seq1b, "Same key must produce identical permutation sequence");

        boolean differs = false;
        for (int i = 0; i < 32; i++) {
            if (seq1a[i] != seq2[i]) {
                differs = true;
                break;
            }
        }
        assertTrue(differs, "Different keys must produce different permutations at at least one of first 32 positions");

        layout1a.close();
        layout1b.close();
        layout2.close();
    }

    @Test
    void testPrefixConsistency() {
        int totalPixels = 2000;
        byte[] key = new byte[32];
        random.nextBytes(key);

        ScatteredLayout instance1 = new ScatteredLayout(totalPixels, key);
        ScatteredLayout instance2 = new ScatteredLayout(totalPixels, key);

        int[] prefix100FromInstance1 = new int[100];
        for (int i = 0; i < 100; i++) {
            prefix100FromInstance1[i] = instance1.nextPixelIndex();
        }

        int[] prefix900FromInstance2 = new int[900];
        for (int i = 0; i < 900; i++) {
            prefix900FromInstance2[i] = instance2.nextPixelIndex();
        }

        for (int i = 0; i < 100; i++) {
            assertEquals(prefix100FromInstance1[i], prefix900FromInstance2[i],
                    "Prefix element at index " + i + " must match regardless of total subsequent draws");
        }

        instance1.close();
        instance2.close();
    }

    @Test
    void testBoundedGeneratorProperties() {
        byte[] key = new byte[32];
        random.nextBytes(key);
        ChaCha20Stream stream = new ChaCha20Stream(key);

        int[] bounds = {1, 2, 3, 1000, 1 << 26};
        for (int bound : bounds) {
            for (int i = 0; i < 1000; i++) {
                int val = stream.nextBounded(bound);
                assertTrue(val >= 0 && val < bound, "Output " + val + " out of range [0, " + bound + ")");
            }
        }

        // Statistical distribution test for bound = 3:
        // Theoretical probability for each residue in {0, 1, 2} is 1/3 (approx 0.333333).
        // Tolerance: Within 1% (0.01) of 1/3, meaning observed frequency must fall in [0.3233, 0.3433].
        int totalDraws = 300_000;
        int[] counts = new int[3];
        for (int i = 0; i < totalDraws; i++) {
            counts[stream.nextBounded(3)]++;
        }

        double expectedProportion = 1.0 / 3.0;
        double tolerance = 0.01; // 1% tolerance
        for (int residue = 0; residue < 3; residue++) {
            double observedProportion = (double) counts[residue] / totalDraws;
            double error = Math.abs(observedProportion - expectedProportion);
            assertTrue(error <= tolerance, String.format(
                    "Residue %d frequency %f deviates from 1/3 by %f (tolerance %f)",
                    residue, observedProportion, error, tolerance));
        }

        stream.close();
    }

    @Test
    void testFullRoundTripWithScatterOn() throws IOException {
        SteganoEngine engine = new SteganoEngine();

        // 0-byte roundtrip
        BufferedImage carrier0 = createCarrier(25, 25);
        engine.cloak(carrier0, new byte[0], "", "ScatterPassword123".toCharArray(), true);
        BufferedImage decoded0 = roundTripPng(carrier0);
        PayloadEnvelope.Unpacked unpacked0 = engine.reveal(decoded0, "ScatterPassword123".toCharArray());
        assertEquals(0, unpacked0.fileBytes().length);
        assertEquals("", unpacked0.extension());

        // 10 KB roundtrip
        BufferedImage carrier10k = createCarrier(180, 180);
        byte[] payload10k = new byte[10240];
        random.nextBytes(payload10k);
        engine.cloak(carrier10k, payload10k, "pdf", "ScatterPassword123".toCharArray(), true);
        BufferedImage decoded10k = roundTripPng(carrier10k);
        PayloadEnvelope.Unpacked unpacked10k = engine.reveal(decoded10k, "ScatterPassword123".toCharArray());
        assertArrayEquals(payload10k, unpacked10k.fileBytes());
        assertEquals("pdf", unpacked10k.extension());

        // Full capacity roundtrip
        int width = 30;
        int height = 30;
        BufferedImage carrierExact = createCarrier(width, height);
        SequentialLayout layout = new SequentialLayout(width * height);
        long capacityBytes = layout.capacityBits() / 8L;
        int exactSize = (int) (capacityBytes - 17L);
        byte[] payloadExact = new byte[exactSize];
        random.nextBytes(payloadExact);
        engine.cloak(carrierExact, payloadExact, "", "ScatterPassword123".toCharArray(), true);
        BufferedImage decodedExact = roundTripPng(carrierExact);
        PayloadEnvelope.Unpacked unpackedExact = engine.reveal(decodedExact, "ScatterPassword123".toCharArray());
        assertArrayEquals(payloadExact, unpackedExact.fileBytes());
    }

    @Test
    void testSequentialModeStillRoundTrips() throws IOException {
        SteganoEngine engine = new SteganoEngine();
        BufferedImage carrier = createCarrier(30, 30);
        byte[] payload = "SequentialModeVerification".getBytes(StandardCharsets.UTF_8);
        char[] password = "SequentialPass".toCharArray();

        // Scattering explicitly OFF
        engine.cloak(carrier, payload, "txt", password, false);
        BufferedImage decoded = roundTripPng(carrier);

        PayloadEnvelope.Unpacked unpacked = engine.reveal(decoded, "SequentialPass".toCharArray());
        assertArrayEquals(payload, unpacked.fileBytes());
        assertEquals("txt", unpacked.extension());
    }

    @Test
    void testFlippingLargeFractionOfPayloadRegionPixelsThrowsAuthenticationFailed() {
        SteganoEngine engine = new SteganoEngine();
        BufferedImage carrier = createCarrier(35, 35);
        byte[] payload = "PayloadIntegrityUnderScattering".getBytes(StandardCharsets.UTF_8);
        char[] password = "TamperPassword".toCharArray();

        engine.cloak(carrier, payload, "txt", password, true);

        // Flip LSBs of 50% of the payload region pixels
        int totalPixels = carrier.getWidth() * carrier.getHeight();
        int[] pixels = carrier.getRGB(0, 0, carrier.getWidth(), carrier.getHeight(), null, 0, carrier.getWidth());
        for (int p = SequentialLayout.HEADER_PIXELS; p < totalPixels; p += 2) {
            pixels[p] ^= 0x01; // flip Blue channel LSB
        }
        carrier.setRGB(0, 0, carrier.getWidth(), carrier.getHeight(), pixels, 0, carrier.getWidth());

        assertThrows(
                AuthenticationFailedException.class,
                () -> engine.reveal(carrier, "TamperPassword".toCharArray())
        );
    }

    @Test
    void testHeaderPixelsIsolatedFromScatteredPayload() {
        // Design of assertion:
        // Header bits (50 bytes = 400 bits) are embedded sequentially into pixels 0..133 (order R, G, B).
        // Even with scattered layout enabled for payload embedding, the permutation pool strictly
        // begins at index 134 (HEADER_PIXELS). Therefore, pixels 0..133 must NEVER be touched by payload bits.
        // We verify that pixels 0..133 in a scattered embed are bit-for-bit identical to pixels 0..133
        // in a sequential embed of the identical header with no payload bits written.
        SteganoEngine engine = new SteganoEngine();
        int width = 30;
        int height = 30;
        BufferedImage carrierScattered = createCarrier(width, height);
        BufferedImage carrierHeaderOnly = createCarrier(width, height);

        byte[] payload = "ScatteredHeaderIsolationCheck".getBytes(StandardCharsets.UTF_8);
        byte[] salt = new byte[16];
        byte[] nonce = new byte[12];
        byte[] scatterKey = new byte[32];
        random.nextBytes(salt);
        random.nextBytes(nonce);
        random.nextBytes(scatterKey);

        EnvelopeHeader header = new EnvelopeHeader(
                EnvelopeHeader.CIPHER_ID_AES_256_GCM,
                EnvelopeHeader.KDF_ID_ARGON2ID,
                EnvelopeHeader.FLAG_SCATTERED,
                (byte) 0,
                65536,
                3,
                1,
                salt,
                nonce,
                payload.length
        );

        // Carrier 1: Header + scattered payload
        engine.cloakRaw(carrierScattered, header, payload, scatterKey);

        // Carrier 2: Same header, empty payload (header only)
        engine.cloakRaw(carrierHeaderOnly, header, new byte[0], scatterKey);

        int[] pixelsScattered = carrierScattered.getRGB(0, 0, width, height, null, 0, width);
        int[] pixelsHeaderOnly = carrierHeaderOnly.getRGB(0, 0, width, height, null, 0, width);

        // Pixels 0..133 must be identical
        for (int p = 0; p < SequentialLayout.HEADER_PIXELS; p++) {
            assertEquals(pixelsHeaderOnly[p], pixelsScattered[p],
                    "Header pixel " + p + " must be identical between header-only and scattered payload carrier");
        }

        // Pixels in payload region (134..N-1) must differ because payload was embedded
        boolean payloadRegionModified = false;
        for (int p = SequentialLayout.HEADER_PIXELS; p < width * height; p++) {
            if (pixelsHeaderOnly[p] != pixelsScattered[p]) {
                payloadRegionModified = true;
                break;
            }
        }
        assertTrue(payloadRegionModified, "Payload region must be modified in scattered embed");
    }

    @Test
    void testModifiedPixelsNotContiguousPrefixOfPool() {
        SteganoEngine engine = new SteganoEngine();
        // Carrier of 100x100 = 10,000 pixels (pool size = 9,866 pixels)
        int width = 100;
        int height = 100;
        BufferedImage carrier = createCarrier(width, height);

        // Create payload that touches >= 100 pixels (each pixel stores 3 bits).
        // 100 pixels * 3 bits = 300 bits = 38 bytes.
        // 50 bytes -> 400 bits -> touches ceil(400 / 3) = 134 pixels
        byte[] payload = new byte[50];
        random.nextBytes(payload);
        char[] password = "NonContiguousPassword".toCharArray();

        int[] beforePixels = carrier.getRGB(0, 0, width, height, null, 0, width);
        engine.cloak(carrier, payload, "bin", password, true);
        int[] afterPixels = carrier.getRGB(0, 0, width, height, null, 0, width);

        List<Integer> modifiedIndices = new ArrayList<>();
        for (int p = SequentialLayout.HEADER_PIXELS; p < width * height; p++) {
            if (beforePixels[p] != afterPixels[p]) {
                modifiedIndices.add(p);
            }
        }

        assertTrue(modifiedIndices.size() >= 50, "At least 50 payload pixels must be modified");

        int minModified = modifiedIndices.get(0);
        int maxModified = modifiedIndices.get(modifiedIndices.size() - 1);

        // In a contiguous sequential layout, maxModified would be SequentialLayout.HEADER_PIXELS + modifiedCount.
        // In a scattered layout across 10,000 pixels, maxModified is spread far into the pool.
        int contiguousMax = SequentialLayout.HEADER_PIXELS + modifiedIndices.size() + 50;
        assertTrue(maxModified > contiguousMax, String.format(
                "Max modified pixel index %d must be far above contiguous boundary %d in scattered pool of %d pixels",
                maxModified, contiguousMax, width * height));
    }

    @Test
    void testMaximumPixelCountGuardThrowsIllegalArgumentException() {
        int overLimitPixels = ScatteredLayout.MAX_PIXEL_COUNT + 1;
        byte[] key = new byte[32];

        assertThrows(
                IllegalArgumentException.class,
                () -> new ScatteredLayout(overLimitPixels, key)
        );
    }
}
