package org.ghost.core;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Property-based randomized round trip testing across 100 fixed-seed iterations:
 * - Random small width / height
 * - Random payload lengths 0..capacity
 * - Random layout flags (all 4 combinations of scatter x adaptive)
 * - Random Unicode passwords with non-BMP code points and combining marks
 * - Real in-memory PNG ImageIO round trip asserting bit-for-bit byte equality
 */
public class RandomizedPropertyTest {

    private static final String[] UNICODE_PASSWORD_FRAGMENTS = {
            "NyxSecret",
            "p\u00E5ssw\u00F8rd",         // Latin-1 supplement (å, ø)
            "cl\u00F4ak\u00E9",          // Accents (ô, é)
            "\uD83D\uDC7B",              // Non-BMP code point (U+1F47B)
            "\uD83D\uDD12",              // Non-BMP code point (U+1F512)
            "\uD83D\uDEE1\uFE0F",        // Non-BMP code point with variation selector (U+1F6E1, U+FE0F)
            "e\u0301",                   // Combining acute mark (e + \u0301)
            "n\u0303",                   // Combining tilde mark (n + \u0303)
            "\u4E2D\u6587\u5BC6\u7801",  // CJK characters (中文密码)
            "\u041F\u0430\u0440\u043E\u043B\u044C", // Cyrillic (Пароль)
            "!@#$%^&*()_+-=[]{}|;:<>?"
    };

    private static final String[] EXTENSIONS = {
            "", "bin", "dat", "txt", "pdf", "zip", "png", "a1b2c3d4e5f6g7h8".substring(0, 16)
    };

    @Test
    void testRandomizedRoundTripProperty100Iterations() throws IOException {
        SteganoEngine engine = new SteganoEngine();
        Random rng = new Random(0xBEEFCAFE777L);

        for (int iter = 0; iter < 100; iter++) {
            // Dimensions: 15..35 width and height (at least 225 pixels > 134 header pixels)
            int width = rng.nextInt(21) + 15;
            int height = rng.nextInt(21) + 15;
            int totalPixels = width * height;

            BufferedImage carrier = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            int[] px = RasterAccess.directPixels(carrier);
            for (int i = 0; i < px.length; i++) {
                px[i] = 0xFF000000 | rng.nextInt(0x01000000);
            }

            // Flags: all 4 combinations
            boolean scatter = rng.nextBoolean();
            boolean adaptive = rng.nextBoolean();

            // Compute carrier capacity
            long availableBits;
            if (adaptive) {
                int[] mask = SobelEdgeDetector.computeMask(px, width, height, DepthProfile.DEFAULT);
                long sum = 0L;
                for (int p = SequentialLayout.HEADER_PIXELS; p < totalPixels; p++) {
                    sum += 3L * (long) mask[p];
                }
                availableBits = sum;
            } else {
                availableBits = (long) (totalPixels - SequentialLayout.HEADER_PIXELS) * 3L;
            }

            String ext = EXTENSIONS[rng.nextInt(EXTENSIONS.length)];
            int overheadBytes = 16 + 1 + ext.length(); // Tag (16) + extLen (1) + ext.length()
            long maxPayloadBytes = Math.max(0L, (availableBits / 8L) - overheadBytes);

            // Random payload length in 0..maxPayloadBytes
            int payloadLen = (maxPayloadBytes == 0) ? 0 : rng.nextInt((int) maxPayloadBytes + 1);
            byte[] payload = new byte[payloadLen];
            rng.nextBytes(payload);

            // Random Unicode password
            StringBuilder pwdBuilder = new StringBuilder();
            int parts = rng.nextInt(4) + 1;
            for (int p = 0; p < parts; p++) {
                pwdBuilder.append(UNICODE_PASSWORD_FRAGMENTS[rng.nextInt(UNICODE_PASSWORD_FRAGMENTS.length)]);
            }
            char[] password = pwdBuilder.toString().toCharArray();

            // Fast Argon2 (8192 KiB, 1 iter, 1 parallelism) for property test
            BufferedImage stego = engine.cloakWithCustomArgon2(
                    carrier, payload, ext, password.clone(), scatter, adaptive, 8192, 1, 1);

            // ImageIO PNG round trip
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            boolean written = ImageIO.write(stego, "png", baos);
            if (!written) {
                throw new IllegalStateException("Failed to write PNG in iteration " + iter);
            }
            BufferedImage decodedImage = ImageIO.read(new ByteArrayInputStream(baos.toByteArray()));

            // Reveal and assert equality
            PayloadEnvelope.Unpacked unpacked = engine.reveal(decodedImage, password.clone());
            assertEquals(ext, unpacked.extension(),
                    String.format("Iteration %d: extension mismatch (w=%d, h=%d, scat=%b, adapt=%b)",
                            iter, width, height, scatter, adaptive));
            assertArrayEquals(payload, unpacked.fileBytes(),
                    String.format("Iteration %d: payload byte mismatch (w=%d, h=%d, scat=%b, adapt=%b, len=%d)",
                            iter, width, height, scatter, adaptive, payloadLen));
        }
    }
}
