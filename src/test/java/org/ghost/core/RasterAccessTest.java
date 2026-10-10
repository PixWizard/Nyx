package org.ghost.core;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.awt.image.DataBufferInt;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class RasterAccessTest {

    private BufferedImage roundTripPng(BufferedImage image) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        boolean written = ImageIO.write(image, "png", baos);
        if (!written) {
            throw new IllegalStateException("Failed to write PNG image");
        }
        return ImageIO.read(new ByteArrayInputStream(baos.toByteArray()));
    }

    private BufferedImage createRandomArgbImage(int width, int height, long seed) {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        int[] pixels = RasterAccess.directPixels(img);
        assertNotNull(pixels);
        Random rng = new Random(seed);
        for (int i = 0; i < pixels.length; i++) {
            int a = 0xFF;
            int r = rng.nextInt(256);
            int g = rng.nextInt(256);
            int b = rng.nextInt(256);
            pixels[i] = (a << 24) | (r << 16) | (g << 8) | b;
        }
        return img;
    }

    /**
     * Verifies that BufferedImage.TYPE_INT_ARGB is backed by DataBufferInt.
     */
    @Test
    void testFactCheckDataBuffer() {
        BufferedImage img = new BufferedImage(10, 10, BufferedImage.TYPE_INT_ARGB);
        assertTrue(img.getRaster().getDataBuffer() instanceof DataBufferInt,
                "TYPE_INT_ARGB must be backed by DataBufferInt");
        assertThrows(ClassCastException.class, () -> {
            DataBufferByte ignored = (DataBufferByte) img.getRaster().getDataBuffer();
        }, "Casting DataBufferInt to DataBufferByte must throw ClassCastException");
    }

    /**
     * Unit test for RasterAccess.directPixels safety validation criteria.
     */
    @Test
    void testRasterAccessSafetyValidation() {
        int width = 40;
        int height = 40;

        // Valid canonical TYPE_INT_ARGB image
        BufferedImage canonical = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        int[] pixels = RasterAccess.directPixels(canonical);
        assertNotNull(pixels, "Valid TYPE_INT_ARGB must return backing int[]");
        assertEquals(width * height, pixels.length);

        // Subimage must be rejected
        BufferedImage subimage = canonical.getSubimage(5, 5, 20, 20);
        assertNull(RasterAccess.directPixels(subimage), "Subimage must fail directPixels validation");

        // TYPE_3BYTE_BGR must be rejected
        BufferedImage bgr = new BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR);
        assertNull(RasterAccess.directPixels(bgr), "TYPE_3BYTE_BGR must fail directPixels validation");

        // TYPE_INT_ARGB_PRE must be rejected (premultiplied alpha)
        BufferedImage argbPre = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB_PRE);
        assertNull(RasterAccess.directPixels(argbPre), "TYPE_INT_ARGB_PRE must fail directPixels validation");

        // TYPE_BYTE_GRAY must be rejected
        BufferedImage gray = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);
        assertNull(RasterAccess.directPixels(gray), "TYPE_BYTE_GRAY must fail directPixels validation");

        // null must return null without throwing
        assertNull(RasterAccess.directPixels(null), "null image must safely return null");
    }

    /**
     * EQUIVALENCE TEST:
     * For several random images and payloads, cloak using the reference getRGB/setRGB path
     * and the direct raster path with the same password, injected salt, and nonce.
     * Assert the resulting pixel arrays are bit-for-bit identical, and reveal through both paths.
     */
    @Test
    void testBitForBitEquivalenceDirectVsReference() {
        SteganoEngine directEngine = new SteganoEngine();
        ReferenceSteganoEngine referenceEngine = new ReferenceSteganoEngine();

        char[] password = "EquivalencePassword2026!".toCharArray();
        byte[] salt = new byte[16];
        byte[] nonce = new byte[12];
        Random rng = new Random(424242L);
        rng.nextBytes(salt);
        rng.nextBytes(nonce);

        int[][] dimensions = {
                {60, 60},
                {80, 70},
                {100, 80}
        };

        boolean[] scatterOptions = {false, true};
        boolean[] adaptiveOptions = {false, true};

        for (int[] dim : dimensions) {
            int w = dim[0];
            int h = dim[1];

            for (boolean scatter : scatterOptions) {
                for (boolean adaptive : adaptiveOptions) {
                    BufferedImage baseImg = createRandomArgbImage(w, h, rng.nextLong());
                    int[] basePixels = RasterAccess.directPixels(baseImg);

                    // Create two identical carriers
                    BufferedImage directCarrier = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
                    BufferedImage refCarrier = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);

                    System.arraycopy(basePixels, 0, RasterAccess.directPixels(directCarrier), 0, basePixels.length);
                    System.arraycopy(basePixels, 0, RasterAccess.directPixels(refCarrier), 0, basePixels.length);

                    byte[] payload = new byte[256];
                    rng.nextBytes(payload);

                    // Cloak with direct engine
                    directEngine.cloakWithFixedCrypto(directCarrier, payload, "txt", password.clone(),
                            scatter, adaptive, salt, nonce);

                    // Cloak with reference engine
                    referenceEngine.cloakWithFixedCrypto(refCarrier, payload, "txt", password.clone(),
                            scatter, adaptive, salt, nonce);

                    // Equivalence assertion: pixel arrays must be bit-for-bit identical
                    int[] directResultPixels = RasterAccess.directPixels(directCarrier);
                    int[] refResultPixels = RasterAccess.directPixels(refCarrier);

                    assertArrayEquals(refResultPixels, directResultPixels,
                            String.format("Pixel buffers must be bit-for-bit identical for %dx%d, scatter=%b, adaptive=%b",
                                    w, h, scatter, adaptive));

                    // Reveal equivalence: direct path
                    PayloadEnvelope.Unpacked directRevealed = directEngine.reveal(directCarrier, password.clone());
                    assertEquals("txt", directRevealed.extension());
                    assertArrayEquals(payload, directRevealed.fileBytes());

                    // Reveal equivalence: reference path
                    PayloadEnvelope.Unpacked refRevealed = referenceEngine.reveal(refCarrier, password.clone());
                    assertEquals("txt", refRevealed.extension());
                    assertArrayEquals(payload, refRevealed.fileBytes());

                    // Cross-reveal: direct cloaked revealed by reference, and vice-versa
                    PayloadEnvelope.Unpacked cross1 = referenceEngine.reveal(directCarrier, password.clone());
                    assertArrayEquals(payload, cross1.fileBytes());

                    PayloadEnvelope.Unpacked cross2 = directEngine.reveal(refCarrier, password.clone());
                    assertArrayEquals(payload, cross2.fileBytes());
                }
            }
        }
    }

    /**
     * VALIDATION FALLBACKS:
     * A subimage (getSubimage), a TYPE_3BYTE_BGR image, a TYPE_INT_ARGB_PRE image,
     * and a TYPE_BYTE_GRAY image must all round-trip correctly through the fallback path with no exception.
     */
    @Test
    void testValidationFallbacksRoundTrip() {
        SteganoEngine engine = new SteganoEngine();
        char[] password = "FallbackPassword!".toCharArray();
        byte[] payload = "TestingValidationFallbacksData".getBytes(StandardCharsets.UTF_8);

        // Subimage fallback
        BufferedImage parent = new BufferedImage(100, 100, BufferedImage.TYPE_INT_ARGB);
        Graphics2D gParent = parent.createGraphics();
        gParent.setColor(java.awt.Color.BLUE);
        gParent.fillRect(0, 0, 100, 100);
        gParent.dispose();

        BufferedImage subimage = parent.getSubimage(10, 10, 80, 80);
        BufferedImage stegoSub = engine.cloak(subimage, payload, "dat", password.clone(), true, false);
        PayloadEnvelope.Unpacked unpackedSub = engine.reveal(stegoSub, password.clone());
        assertEquals("dat", unpackedSub.extension());
        assertArrayEquals(payload, unpackedSub.fileBytes(), "Subimage fallback must round-trip payload");

        // TYPE_3BYTE_BGR fallback
        BufferedImage bgr = new BufferedImage(80, 80, BufferedImage.TYPE_3BYTE_BGR);
        Graphics2D gBgr = bgr.createGraphics();
        gBgr.setColor(java.awt.Color.DARK_GRAY);
        gBgr.fillRect(0, 0, 80, 80);
        gBgr.dispose();

        BufferedImage stegoBgr = engine.cloak(bgr, payload, "bin", password.clone(), true, false);
        PayloadEnvelope.Unpacked unpackedBgr = engine.reveal(stegoBgr, password.clone());
        assertEquals("bin", unpackedBgr.extension());
        assertArrayEquals(payload, unpackedBgr.fileBytes(), "TYPE_3BYTE_BGR fallback must round-trip payload");

        // TYPE_INT_ARGB_PRE fallback
        BufferedImage pre = new BufferedImage(80, 80, BufferedImage.TYPE_INT_ARGB_PRE);
        Graphics2D gPre = pre.createGraphics();
        gPre.setColor(java.awt.Color.GREEN);
        gPre.fillRect(0, 0, 80, 80);
        gPre.dispose();

        BufferedImage stegoPre = engine.cloak(pre, payload, "txt", password.clone(), true, false);
        PayloadEnvelope.Unpacked unpackedPre = engine.reveal(stegoPre, password.clone());
        assertEquals("txt", unpackedPre.extension());
        assertArrayEquals(payload, unpackedPre.fileBytes(), "TYPE_INT_ARGB_PRE fallback must round-trip payload");

        // TYPE_BYTE_GRAY fallback
        BufferedImage gray = new BufferedImage(80, 80, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D gGray = gray.createGraphics();
        gGray.setColor(java.awt.Color.GRAY);
        gGray.fillRect(0, 0, 80, 80);
        gGray.dispose();

        BufferedImage stegoGray = engine.cloak(gray, payload, "pdf", password.clone(), true, false);
        PayloadEnvelope.Unpacked unpackedGray = engine.reveal(stegoGray, password.clone());
        assertEquals("pdf", unpackedGray.extension());
        assertArrayEquals(payload, unpackedGray.fileBytes(), "TYPE_BYTE_GRAY fallback must round-trip payload");
    }

    /**
     * Confirms alpha channel is never modified and PNG round-trips losslessly.
     */
    @Test
    void testAlphaChannelPreservationAndPngLosslessRoundTrip() throws IOException {
        SteganoEngine engine = new SteganoEngine();
        int width = 70;
        int height = 70;
        BufferedImage carrier = createRandomArgbImage(width, height, 9999L);
        int[] originalPixels = Arrays.copyOf(RasterAccess.directPixels(carrier), width * height);

        byte[] payload = new byte[512];
        new Random(555L).nextBytes(payload);
        char[] password = "AlphaPreservation!".toCharArray();

        engine.cloak(carrier, payload, "bin", password.clone(), true, true);
        int[] stegoPixels = RasterAccess.directPixels(carrier);

        // Assert alpha channel is strictly unchanged across all pixels
        for (int p = 0; p < originalPixels.length; p++) {
            int originalAlpha = (originalPixels[p] >>> 24) & 0xFF;
            int stegoAlpha = (stegoPixels[p] >>> 24) & 0xFF;
            assertEquals(originalAlpha, stegoAlpha, "Alpha channel must be strictly unchanged at pixel " + p);
        }

        // Lossless PNG round-trip
        BufferedImage pngRestored = roundTripPng(carrier);
        PayloadEnvelope.Unpacked unpacked = engine.reveal(pngRestored, password.clone());
        assertEquals("bin", unpacked.extension());
        assertArrayEquals(payload, unpacked.fileBytes(), "PNG round-trip must decode losslessly");
    }

    @Test
    void testCarrierImageFormatRoundTrips() throws IOException {
        SteganoEngine engine = new SteganoEngine();
        byte[] payload = "FormatCompatibilityTestData".getBytes(StandardCharsets.UTF_8);
        char[] password = "FormatSecretPass!".toCharArray();
        int width = 80;
        int height = 80;

        // TYPE_BYTE_GRAY
        BufferedImage grayImg = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D gGray = grayImg.createGraphics();
        for (int y = 0; y < height; y++) {
            gGray.setColor(new java.awt.Color((y * 255) / height, (y * 255) / height, (y * 255) / height));
            gGray.drawLine(0, y, width, y);
        }
        gGray.dispose();
        BufferedImage argbFromGray = org.ghost.utils.ImageManager.convertToARGB(grayImg);
        BufferedImage stegoGray = engine.cloak(argbFromGray, payload, "txt", password.clone(), true, true);
        BufferedImage restoredPngGray = roundTripPng(stegoGray);
        PayloadEnvelope.Unpacked unpackedGray = engine.reveal(restoredPngGray, password.clone());
        assertArrayEquals(payload, unpackedGray.fileBytes());
        assertEquals("txt", unpackedGray.extension());

        // TYPE_BYTE_INDEXED (Indexed color palette)
        BufferedImage indexedImg = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_INDEXED);
        Graphics2D gInd = indexedImg.createGraphics();
        gInd.setColor(java.awt.Color.RED);
        gInd.fillRect(0, 0, width, height / 2);
        gInd.setColor(java.awt.Color.BLUE);
        gInd.fillRect(0, height / 2, width, height / 2);
        gInd.dispose();
        BufferedImage argbFromIndexed = org.ghost.utils.ImageManager.convertToARGB(indexedImg);
        BufferedImage stegoIndexed = engine.cloak(argbFromIndexed, payload, "bin", password.clone(), true, false);
        BufferedImage restoredPngIndexed = roundTripPng(stegoIndexed);
        PayloadEnvelope.Unpacked unpackedIndexed = engine.reveal(restoredPngIndexed, password.clone());
        assertArrayEquals(payload, unpackedIndexed.fileBytes());
        assertEquals("bin", unpackedIndexed.extension());

        // TYPE_USHORT_GRAY (16-bit per channel)
        BufferedImage ushortGrayImg = new BufferedImage(width, height, BufferedImage.TYPE_USHORT_GRAY);
        Graphics2D gUshort = ushortGrayImg.createGraphics();
        gUshort.setColor(java.awt.Color.GRAY);
        gUshort.fillRect(0, 0, width, height);
        gUshort.dispose();
        BufferedImage argbFromUshort = org.ghost.utils.ImageManager.convertToARGB(ushortGrayImg);
        BufferedImage stegoUshort = engine.cloak(argbFromUshort, payload, "dat", password.clone(), false, false);
        BufferedImage restoredPngUshort = roundTripPng(stegoUshort);
        PayloadEnvelope.Unpacked unpackedUshort = engine.reveal(restoredPngUshort, password.clone());
        assertArrayEquals(payload, unpackedUshort.fileBytes());
        assertEquals("dat", unpackedUshort.extension());

        // PNG with alpha (including pixels with alpha = 0)
        BufferedImage transparentImg = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        int[] transPixels = RasterAccess.directPixels(transparentImg);
        Random rng = new Random(333L);
        for (int i = 0; i < transPixels.length; i++) {
            // First 500 pixels have alpha = 0
            int a = (i < 500) ? 0 : 0xFF;
            int r = rng.nextInt(256);
            int g = rng.nextInt(256);
            int b = rng.nextInt(256);
            transPixels[i] = (a << 24) | (r << 16) | (g << 8) | b;
        }
        BufferedImage stegoTrans = engine.cloak(transparentImg, payload, "png", password.clone(), true, true);
        BufferedImage restoredPngTrans = roundTripPng(stegoTrans);
        PayloadEnvelope.Unpacked unpackedTrans = engine.reveal(restoredPngTrans, password.clone());
        assertArrayEquals(payload, unpackedTrans.fileBytes());
        assertEquals("png", unpackedTrans.extension());
    }
}
