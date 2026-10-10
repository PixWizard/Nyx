package org.ghost.core;

import org.ghost.crypto.AuthenticationFailedException;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Robustness fuzz testing covering 200 iterations of random pixel buffers and 200 iterations
 * of random bit-flipped stego images.
 */
public class RobustnessFuzzTest {

    private static final Duration ITERATION_TIMEOUT = Duration.ofMillis(500);

    @Test
    void testFuzzRandomPixelBuffers() {
        SteganoEngine engine = new SteganoEngine();
        Random rng = new Random(0xCAFE1337L);
        char[] password = "FuzzPasswordTest2026!".toCharArray();

        for (int iter = 0; iter < 200; iter++) {
            int width = rng.nextInt(90) + 1; // 1..90
            int height = rng.nextInt(90) + 1; // 1..90
            BufferedImage randomImage = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            int[] pixels = RasterAccess.directPixels(randomImage);
            for (int i = 0; i < pixels.length; i++) {
                pixels[i] = rng.nextInt();
            }

            final int iteration = iter;
            assertTimeoutPreemptively(ITERATION_TIMEOUT, () -> {
                try {
                    engine.reveal(randomImage, password.clone());
                    // If by astronomical chance a random buffer satisfies magic, CRC, and AES-GCM tag, it's valid.
                } catch (NoNyxPayloadException | InvalidHeaderException | UnsupportedFormatException |
                         AuthenticationFailedException | InsufficientCarrierCapacityException expected) {
                    // Allowed defined exceptions
                } catch (NullPointerException | ArrayIndexOutOfBoundsException | NegativeArraySizeException |
                         ArithmeticException | OutOfMemoryError err) {
                    fail(String.format("Iteration %d (size %dx%d) threw disallowed exception: %s: %s",
                            iteration, width, height, err.getClass().getName(), err.getMessage()));
                } catch (Throwable other) {
                    fail(String.format("Iteration %d (size %dx%d) threw unexpected exception: %s: %s",
                            iteration, width, height, other.getClass().getName(), other.getMessage()));
                }
            }, () -> "Iteration " + iteration + " timed out after " + ITERATION_TIMEOUT);
        }
    }

    @Test
    void testFuzzBitFlippedStegoImages() {
        SteganoEngine engine = new SteganoEngine();
        Random rng = new Random(0xDEADBEEFL);
        char[] password = "FuzzBitFlipPassword!".toCharArray();
        byte[] payload = "FuzzTestingPayloadContent123456789".getBytes(StandardCharsets.UTF_8);

        int width = 50;
        int height = 50;
        int totalPixels = width * height;

        // Base carrier
        BufferedImage baseCarrier = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        int[] basePx = RasterAccess.directPixels(baseCarrier);
        for (int i = 0; i < basePx.length; i++) {
            basePx[i] = 0xFF000000 | rng.nextInt(0x01000000);
        }

        // Fast Argon2 parameters: 8192 KiB (MIN_ARGON2_MEMORY_KIB), 1 iteration, 1 parallelism
        BufferedImage baseStego = engine.cloakWithCustomArgon2(
                baseCarrier, payload, "txt", password.clone(), true, true, 8192, 1, 1);
        int[] originalStegoPixels = RasterAccess.directPixels(baseStego);

        for (int iter = 0; iter < 200; iter++) {
            BufferedImage testImg = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            int[] testPx = RasterAccess.directPixels(testImg);
            System.arraycopy(originalStegoPixels, 0, testPx, 0, originalStegoPixels.length);

            // Flip between 1 and 25 random bits across random pixels (including header pixels 0..133)
            int numFlips = rng.nextInt(25) + 1;
            for (int f = 0; f < numFlips; f++) {
                int targetPixel = rng.nextInt(totalPixels);
                int bitToFlip = rng.nextInt(32);
                testPx[targetPixel] ^= (1 << bitToFlip);
            }

            final int iteration = iter;
            assertTimeoutPreemptively(ITERATION_TIMEOUT, () -> {
                try {
                    engine.reveal(testImg, password.clone());
                } catch (NoNyxPayloadException | InvalidHeaderException | UnsupportedFormatException |
                         AuthenticationFailedException | InsufficientCarrierCapacityException expected) {
                    // Allowed defined exceptions
                } catch (NullPointerException | ArrayIndexOutOfBoundsException | NegativeArraySizeException |
                         ArithmeticException | OutOfMemoryError err) {
                    fail(String.format("Iteration %d threw disallowed exception: %s: %s",
                            iteration, err.getClass().getName(), err.getMessage()));
                } catch (Throwable other) {
                    fail(String.format("Iteration %d threw unexpected exception: %s: %s",
                            iteration, other.getClass().getName(), other.getMessage()));
                }
            }, () -> "Iteration " + iteration + " timed out after " + ITERATION_TIMEOUT);
        }
    }
}
