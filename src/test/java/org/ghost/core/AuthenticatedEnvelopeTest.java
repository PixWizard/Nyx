package org.ghost.core;

import org.ghost.crypto.AuthenticatedCipher;
import org.ghost.crypto.AuthenticationFailedException;
import org.ghost.crypto.NyxKeyDerivation;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Random;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class AuthenticatedEnvelopeTest {

    private final SecureRandom random = new SecureRandom();

    private BufferedImage createCarrier(int width, int height) {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(100, 140, 180, 255));
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
    void testRoundTripZeroBytes() throws IOException {
        SteganoEngine engine = new SteganoEngine();
        BufferedImage carrier = createCarrier(20, 20); // 400 pixels > 134
        byte[] payload = new byte[0];
        char[] password = "SecretPassword1".toCharArray();

        engine.cloak(carrier, payload, "", password);
        BufferedImage decodedImage = roundTripPng(carrier);

        PayloadEnvelope.Unpacked unpacked = engine.reveal(decodedImage, "SecretPassword1".toCharArray());
        assertArrayEquals(payload, unpacked.fileBytes());
        assertEquals("", unpacked.extension());
    }

    @Test
    void testRoundTripOneByte() throws IOException {
        SteganoEngine engine = new SteganoEngine();
        BufferedImage carrier = createCarrier(20, 20);
        byte[] payload = new byte[]{0x42};
        char[] password = "SecretPassword2".toCharArray();

        engine.cloak(carrier, payload, "bin", password);
        BufferedImage decodedImage = roundTripPng(carrier);

        PayloadEnvelope.Unpacked unpacked = engine.reveal(decodedImage, "SecretPassword2".toCharArray());
        assertArrayEquals(payload, unpacked.fileBytes());
        assertEquals("bin", unpacked.extension());
    }

    @Test
    void testRoundTripTenKbRandom() throws IOException {
        SteganoEngine engine = new SteganoEngine();
        // 10 KB = 10240 bytes.
        // Ciphertext bytes = 1 + 3 + 10240 + 16 = 10260 bytes = 82080 bits.
        // Payload pixels = 82080 / 3 = 27360 pixels.
        // Total pixels needed = 134 + 27360 = 27494 pixels.
        // sqrt(27494) ~ 166; use 180x180 = 32400 pixels
        BufferedImage carrier = createCarrier(180, 180);
        byte[] payload = new byte[10240];
        random.nextBytes(payload);
        char[] password = "TenKbPassword!".toCharArray();

        engine.cloak(carrier, payload, "pdf", password);
        BufferedImage decodedImage = roundTripPng(carrier);

        PayloadEnvelope.Unpacked unpacked = engine.reveal(decodedImage, "TenKbPassword!".toCharArray());
        assertArrayEquals(payload, unpacked.fileBytes());
        assertEquals("pdf", unpacked.extension());
    }

    @Test
    void testRoundTripExactCapacity() throws IOException {
        SteganoEngine engine = new SteganoEngine();
        int width = 30;
        int height = 30; // 900 pixels
        BufferedImage carrier = createCarrier(width, height);

        SequentialLayout layout = new SequentialLayout(width * height);
        long capacityBits = layout.capacityBits();
        long capacityBytes = capacityBits / 8L;

        // Framing: [1 byte extLen] + [0 bytes ext] + [payload] + [16 bytes GCM tag]
        // Total ciphertext = 1 + 0 + payload.length + 16 = payload.length + 17 bytes
        // payload.length = capacityBytes - 17
        int exactPayloadSize = (int) (capacityBytes - 17L);
        byte[] payload = new byte[exactPayloadSize];
        random.nextBytes(payload);
        char[] password = "ExactCapacityPassword".toCharArray();

        engine.cloak(carrier, payload, "", password);
        BufferedImage decodedImage = roundTripPng(carrier);

        PayloadEnvelope.Unpacked unpacked = engine.reveal(decodedImage, "ExactCapacityPassword".toCharArray());
        assertArrayEquals(payload, unpacked.fileBytes());
        assertEquals("", unpacked.extension());
    }

    @Test
    void testRoundTripExtensionEdgeCases() throws IOException {
        SteganoEngine engine = new SteganoEngine();
        String[] extensions = {"", "pdf", "1234567890abcdef"}; // 0 chars, 3 chars, 16 chars

        for (String ext : extensions) {
            BufferedImage carrier = createCarrier(25, 25);
            byte[] payload = ("Test payload for extension: " + ext).getBytes(StandardCharsets.UTF_8);
            char[] password = "ExtPassword123".toCharArray();

            engine.cloak(carrier, payload, ext, password);
            BufferedImage decodedImage = roundTripPng(carrier);

            PayloadEnvelope.Unpacked unpacked = engine.reveal(decodedImage, "ExtPassword123".toCharArray());
            assertArrayEquals(payload, unpacked.fileBytes());
            assertEquals(ext, unpacked.extension());
        }
    }

    @Test
    void testCapacityExceededByOneByteLeavesPixelBufferUntouched() {
        SteganoEngine engine = new SteganoEngine();
        int width = 20;
        int height = 20; // 400 pixels
        BufferedImage carrier = createCarrier(width, height);

        SequentialLayout layout = new SequentialLayout(width * height);
        long capacityBytes = layout.capacityBits() / 8L;

        // Exactly fitting payload size is capacityBytes - 17
        // Exceeding by 1 byte is capacityBytes - 17 + 1 = capacityBytes - 16
        int exceededPayloadSize = (int) (capacityBytes - 16L);
        byte[] payload = new byte[exceededPayloadSize];

        int[] originalPixels = carrier.getRGB(0, 0, width, height, null, 0, width);

        InsufficientCarrierCapacityException ex = assertThrows(
                InsufficientCarrierCapacityException.class,
                () -> engine.cloak(carrier, payload, "", "SomePassword".toCharArray())
        );

        assertEquals(capacityBytes + 1L, ex.getRequiredBytes());
        assertEquals(capacityBytes, ex.getAvailableBytes());

        int[] currentPixels = carrier.getRGB(0, 0, width, height, null, 0, width);
        assertArrayEquals(originalPixels, currentPixels, "Pixel buffer must be completely untouched when capacity check fails");
    }

    @Test
    void testWrongPasswordThrowsAuthenticationFailedException() throws IOException {
        SteganoEngine engine = new SteganoEngine();
        BufferedImage carrier = createCarrier(20, 20);
        byte[] payload = "TopSecret".getBytes(StandardCharsets.UTF_8);

        engine.cloak(carrier, payload, "txt", "CorrectPassword".toCharArray());
        BufferedImage decodedImage = roundTripPng(carrier);

        assertThrows(
                AuthenticationFailedException.class,
                () -> engine.reveal(decodedImage, "WrongPassword".toCharArray())
        );
    }

    @Test
    void testFlippingCiphertextBitThrowsAuthenticationFailedException() throws IOException {
        SteganoEngine engine = new SteganoEngine();
        BufferedImage carrier = createCarrier(25, 25);
        byte[] payload = "IntegrityProtectedPayload".getBytes(StandardCharsets.UTF_8);

        engine.cloak(carrier, payload, "txt", "AuthPass".toCharArray(), false);

        // Tamper with one bit in the payload region (pixel index 134, which is the first payload pixel in sequential mode)
        int[] pixelBuffer = carrier.getRGB(0, 0, carrier.getWidth(), carrier.getHeight(), null, 0, carrier.getWidth());
        pixelBuffer[SequentialLayout.HEADER_PIXELS] ^= 1; // Flip LSB of blue
        carrier.setRGB(0, 0, carrier.getWidth(), carrier.getHeight(), pixelBuffer, 0, carrier.getWidth());

        BufferedImage decodedImage = roundTripPng(carrier);

        assertThrows(
                AuthenticationFailedException.class,
                () -> engine.reveal(decodedImage, "AuthPass".toCharArray())
        );
    }

    @Test
    void testFlippingHeaderByteWithRefixedCrcThrowsAuthenticationFailedException() throws IOException {
        SteganoEngine engine = new SteganoEngine();
        BufferedImage carrier = createCarrier(25, 25);
        byte[] payload = "AadBindingVerification".getBytes(StandardCharsets.UTF_8);

        engine.cloak(carrier, payload, "txt", "AadPassword".toCharArray());

        // Extract raw header, flip flags byte (offset 6), recompute CRC32, write back
        SteganoEngine.RawExtracted raw = engine.revealRaw(carrier);
        EnvelopeHeader origHeader = raw.header();

        byte flippedFlags = (byte) (origHeader.getFlags() ^ 0x01);
        EnvelopeHeader modifiedHeader = new EnvelopeHeader(
                origHeader.getCipherId(),
                origHeader.getKdfId(),
                flippedFlags, // Tampered flags
                origHeader.getProfileId(),
                origHeader.getArgon2MemoryKiB(),
                origHeader.getArgon2Iterations(),
                origHeader.getArgon2Parallelism(),
                origHeader.getSalt(),
                origHeader.getNonce(),
                origHeader.getCiphertextLength()
        );

        // Re-cloak carrier with modified header (CRC is valid) and original ciphertext
        engine.cloakRaw(carrier, modifiedHeader, raw.ciphertext());

        BufferedImage decodedImage = roundTripPng(carrier);

        // Header CRC passes, but AES-GCM AAD check fails!
        assertThrows(
                AuthenticationFailedException.class,
                () -> engine.reveal(decodedImage, "AadPassword".toCharArray())
        );
    }

    @Test
    void testFlipEachHeaderByte0To45WithRefixedCrcThrowsAuthOrHeaderException() {
        SteganoEngine engine = new SteganoEngine();
        byte[] payload = "TestingAllHeaderByteFlipsWithRefixedCrc".getBytes(StandardCharsets.UTF_8);
        char[] password = "HeaderByteFlippingPassword2026!".toCharArray();

        for (int byteOffset = 0; byteOffset < EnvelopeHeader.AAD_SIZE_BYTES; byteOffset++) {
            BufferedImage carrier = createCarrier(30, 30);
            engine.cloak(carrier, payload, "txt", password.clone());

            SteganoEngine.RawExtracted raw = engine.revealRaw(carrier);
            byte[] serialized = raw.header().serialize();

            // Flip 1 bit in byte at byteOffset
            serialized[byteOffset] ^= 0x01;

            // Recompute CRC32 over bytes 0..45
            CRC32 crc = new CRC32();
            crc.update(serialized, 0, EnvelopeHeader.AAD_SIZE_BYTES);
            ByteBuffer bb = ByteBuffer.wrap(serialized).order(ByteOrder.BIG_ENDIAN);
            bb.putInt(EnvelopeHeader.OFFSET_CRC32, (int) crc.getValue());

            // Write modified header back into pixels 0..133
            int[] pixelBuffer = RasterAccess.directPixels(carrier);
            int[] shifts = {16, 8, 0};
            int bitIdx = 0;
            for (int p = 0; p < SequentialLayout.HEADER_PIXELS; p++) {
                int pixel = pixelBuffer[p];
                for (int shift : shifts) {
                    if (bitIdx >= 400) break;
                    int bit = (serialized[bitIdx / 8] >>> (7 - (bitIdx % 8))) & 1;
                    pixel = (pixel & ~(1 << shift)) | (bit << shift);
                    bitIdx++;
                }
                pixelBuffer[p] = pixel;
                if (bitIdx >= 400) break;
            }

            final int offset = byteOffset;
            try {
                engine.reveal(carrier, password.clone());
                org.junit.jupiter.api.Assertions.fail("Offset " + offset + " did not throw any exception!");
            } catch (AuthenticationFailedException | NoNyxPayloadException | InvalidHeaderException | UnsupportedFormatException expected) {
                // Expected failure
            } catch (Throwable unexpected) {
                org.junit.jupiter.api.Assertions.fail("Offset " + offset + " threw unexpected exception: " + unexpected.getClass().getName() + ": " + unexpected.getMessage(), unexpected);
            }
        }
    }

    @Test
    void testPlainPhotoLikeRandomImageThrowsNoNyxPayloadException() {
        SteganoEngine engine = new SteganoEngine();
        BufferedImage plainImage = new BufferedImage(50, 50, BufferedImage.TYPE_INT_ARGB);
        int[] pixels = new int[50 * 50];
        random.nextBytes(new byte[pixels.length * 4]);
        for (int i = 0; i < pixels.length; i++) {
            pixels[i] = 0xFF000000 | (random.nextInt() & 0x00FFFFFF);
        }
        plainImage.setRGB(0, 0, 50, 50, pixels, 0, 50);

        assertThrows(
                NoNyxPayloadException.class,
                () -> engine.reveal(plainImage, "AnyPassword".toCharArray())
        );
    }

    @Test
    void testCraftedHeaderWithMaxCiphertextLengthThrowsInvalidHeaderExceptionWithoutLargeAllocation() {
        SteganoEngine engine = new SteganoEngine();
        BufferedImage carrier = createCarrier(30, 30);

        // Construct crafted header with ciphertext length 0xFFFFFFFF (4294967295) and valid CRC
        byte[] salt = new byte[16];
        byte[] nonce = new byte[12];
        random.nextBytes(salt);
        random.nextBytes(nonce);

        byte[] headerBytes = new byte[EnvelopeHeader.HEADER_SIZE_BYTES];
        ByteBuffer bb = ByteBuffer.wrap(headerBytes).order(ByteOrder.BIG_ENDIAN);
        bb.put(EnvelopeHeader.MAGIC);
        bb.put(EnvelopeHeader.CIPHER_ID_AES_256_GCM);
        bb.put(EnvelopeHeader.KDF_ID_ARGON2ID);
        bb.put((byte) 0); // flags
        bb.put((byte) 0); // profileId
        bb.putInt(NyxKeyDerivation.DEFAULT_ARGON2_MEMORY_KIB);
        bb.put((byte) NyxKeyDerivation.DEFAULT_ARGON2_ITERATIONS);
        bb.put((byte) NyxKeyDerivation.DEFAULT_ARGON2_PARALLELISM);
        bb.put(salt);
        bb.put(nonce);
        bb.putInt((int) 0xFFFFFFFFL); // 4294967295 length

        CRC32 crc = new CRC32();
        crc.update(headerBytes, 0, EnvelopeHeader.AAD_SIZE_BYTES);
        bb.putInt((int) crc.getValue());

        // Embed the crafted header into pixels 0..133
        int[] pixelBuffer = carrier.getRGB(0, 0, carrier.getWidth(), carrier.getHeight(), null, 0, carrier.getWidth());
        int[] shifts = {16, 8, 0};
        int bitIdx = 0;
        for (int p = 0; p < SequentialLayout.HEADER_PIXELS; p++) {
            int pixel = pixelBuffer[p];
            for (int shift : shifts) {
                if (bitIdx >= 400) break;
                int bit = (headerBytes[bitIdx / 8] >> (7 - (bitIdx % 8))) & 1;
                pixel = (pixel & ~(1 << shift)) | (bit << shift);
                bitIdx++;
            }
            pixelBuffer[p] = pixel;
            if (bitIdx >= 400) break;
        }
        carrier.setRGB(0, 0, carrier.getWidth(), carrier.getHeight(), pixelBuffer, 0, carrier.getWidth());

        assertThrows(
                InvalidHeaderException.class,
                () -> engine.reveal(carrier, "AnyPass".toCharArray())
        );
    }

    @Test
    void testCraftedHeaderWithHighBitSetCiphertextLength0x80000000ThrowsInvalidHeaderException() {
        SteganoEngine engine = new SteganoEngine();
        BufferedImage carrier = createCarrier(30, 30);

        // Construct crafted header with ciphertext length 0x80000000 (2147483648) and valid CRC
        byte[] salt = new byte[16];
        byte[] nonce = new byte[12];
        random.nextBytes(salt);
        random.nextBytes(nonce);

        byte[] headerBytes = new byte[EnvelopeHeader.HEADER_SIZE_BYTES];
        ByteBuffer bb = ByteBuffer.wrap(headerBytes).order(ByteOrder.BIG_ENDIAN);
        bb.put(EnvelopeHeader.MAGIC);
        bb.put(EnvelopeHeader.CIPHER_ID_AES_256_GCM);
        bb.put(EnvelopeHeader.KDF_ID_ARGON2ID);
        bb.put((byte) 0); // flags
        bb.put((byte) 0); // profileId
        bb.putInt(NyxKeyDerivation.DEFAULT_ARGON2_MEMORY_KIB);
        bb.put((byte) NyxKeyDerivation.DEFAULT_ARGON2_ITERATIONS);
        bb.put((byte) NyxKeyDerivation.DEFAULT_ARGON2_PARALLELISM);
        bb.put(salt);
        bb.put(nonce);
        bb.putInt((int) 0x80000000L); // 2147483648 length (high bit set)

        CRC32 crc = new CRC32();
        crc.update(headerBytes, 0, EnvelopeHeader.AAD_SIZE_BYTES);
        bb.putInt((int) crc.getValue());

        // Embed the crafted header into pixels 0..133
        int[] pixelBuffer = carrier.getRGB(0, 0, carrier.getWidth(), carrier.getHeight(), null, 0, carrier.getWidth());
        int[] shifts = {16, 8, 0};
        int bitIdx = 0;
        for (int p = 0; p < SequentialLayout.HEADER_PIXELS; p++) {
            int pixel = pixelBuffer[p];
            for (int shift : shifts) {
                if (bitIdx >= 400) break;
                int bit = (headerBytes[bitIdx / 8] >>> (7 - (bitIdx % 8))) & 1;
                pixel = (pixel & ~(1 << shift)) | (bit << shift);
                bitIdx++;
            }
            pixelBuffer[p] = pixel;
            if (bitIdx >= 400) break;
        }
        carrier.setRGB(0, 0, carrier.getWidth(), carrier.getHeight(), pixelBuffer, 0, carrier.getWidth());

        InvalidHeaderException ex = assertThrows(
                InvalidHeaderException.class,
                () -> engine.reveal(carrier, "AnyPass".toCharArray())
        );
        // Ensure message reports unsigned length, not negative
        assertEquals(true, ex.getMessage().contains("2147483648 bytes"));
    }

    @Test
    void testSamePlaintextProducesDifferentSaltAndNonce() {
        SteganoEngine engine = new SteganoEngine();
        BufferedImage carrier1 = createCarrier(25, 25);
        BufferedImage carrier2 = createCarrier(25, 25);

        byte[] payload = "DeterministicTest".getBytes(StandardCharsets.UTF_8);

        engine.cloak(carrier1, payload, "txt", "SamePassword".toCharArray());
        engine.cloak(carrier2, payload, "txt", "SamePassword".toCharArray());

        SteganoEngine.RawExtracted raw1 = engine.revealRaw(carrier1);
        SteganoEngine.RawExtracted raw2 = engine.revealRaw(carrier2);

        assertFalse(Arrays.equals(raw1.header().getSalt(), raw2.header().getSalt()), "Salts must be randomly generated and distinct");
        assertFalse(Arrays.equals(raw1.header().getNonce(), raw2.header().getNonce()), "Nonces must be randomly generated and distinct");
    }

    @Test
    void testInvalidExtensionsRejectedOnPack() {
        byte[] payload = "data".getBytes(StandardCharsets.UTF_8);

        assertThrows(IllegalArgumentException.class, () -> PayloadEnvelope.pack(payload, "../x"));
        assertThrows(IllegalArgumentException.class, () -> PayloadEnvelope.pack(payload, "a/b"));
        assertThrows(IllegalArgumentException.class, () -> PayloadEnvelope.pack(payload, "with.dot"));
        assertThrows(IllegalArgumentException.class, () -> PayloadEnvelope.pack(payload, "toolongextension1234567")); // > 16 chars
    }

    @Test
    void testExactCapacityAndOverflowForLength0And16() {
        SteganoEngine engine = new SteganoEngine();
        int width = 30;
        int height = 30; // 900 pixels
        int totalPixels = width * height;
        long poolPixels = totalPixels - SequentialLayout.HEADER_PIXELS; // 900 - 134 = 766 pixels
        long capacityBits = poolPixels * 3L; // 2298 bits
        long availableCapacityBytes = capacityBits / 8L; // 287 bytes
        char[] password = "ExactCapacityPassword!".toCharArray();

        // 1. Extension length 0 (""): overhead is 16 (GCM tag) + 1 (extLen) + 0 = 17 bytes
        int overheadLen0 = 16 + 1 + 0;
        int exactCapLen0 = (int) (availableCapacityBytes - overheadLen0); // 270 bytes

        byte[] payloadExact0 = new byte[exactCapLen0];
        new Random(101L).nextBytes(payloadExact0);
        BufferedImage carrierExact0 = createCarrier(width, height);
        assertDoesNotThrow(() -> engine.cloak(carrierExact0, payloadExact0, "", password.clone()));
        PayloadEnvelope.Unpacked unpacked0 = engine.reveal(carrierExact0, password.clone());
        assertArrayEquals(payloadExact0, unpacked0.fileBytes());
        assertEquals("", unpacked0.extension());

        byte[] payloadOverflow0 = new byte[exactCapLen0 + 1];
        BufferedImage carrierOverflow0 = createCarrier(width, height);
        assertThrows(InsufficientCarrierCapacityException.class,
                () -> engine.cloak(carrierOverflow0, payloadOverflow0, "", password.clone()));

        // 2. Extension length 16 ("1234567890abcdef"): overhead is 16 (GCM tag) + 1 (extLen) + 16 = 33 bytes
        String ext16 = "1234567890abcdef";
        int overheadLen16 = 16 + 1 + 16;
        int exactCapLen16 = (int) (availableCapacityBytes - overheadLen16); // 254 bytes

        byte[] payloadExact16 = new byte[exactCapLen16];
        new Random(202L).nextBytes(payloadExact16);
        BufferedImage carrierExact16 = createCarrier(width, height);
        assertDoesNotThrow(() -> engine.cloak(carrierExact16, payloadExact16, ext16, password.clone()));
        PayloadEnvelope.Unpacked unpacked16 = engine.reveal(carrierExact16, password.clone());
        assertArrayEquals(payloadExact16, unpacked16.fileBytes());
        assertEquals(ext16, unpacked16.extension());

        byte[] payloadOverflow16 = new byte[exactCapLen16 + 1];
        BufferedImage carrierOverflow16 = createCarrier(width, height);
        assertThrows(InsufficientCarrierCapacityException.class,
                () -> engine.cloak(carrierOverflow16, payloadOverflow16, ext16, password.clone()));
    }

    @Test
    void testEmptyPasswordIsRejectedOnCloakAndReveal() {
        SteganoEngine engine = new SteganoEngine();
        BufferedImage carrier = createCarrier(20, 20);
        byte[] payload = new byte[]{0x42};

        assertThrows(IllegalArgumentException.class, () ->
                engine.cloak(carrier, payload, "txt", new char[0]));
        assertThrows(IllegalArgumentException.class, () ->
                engine.cloak(carrier, payload, "txt", null));
        assertThrows(IllegalArgumentException.class, () ->
                engine.reveal(carrier, new char[0]));
        assertThrows(IllegalArgumentException.class, () ->
                engine.reveal(carrier, null));
        assertThrows(IllegalArgumentException.class, () ->
                NyxKeyDerivation.deriveKeys(new char[0], new byte[16], 65536, 3, 1));
        assertThrows(IllegalArgumentException.class, () ->
                NyxKeyDerivation.deriveKeys(null, new byte[16], 65536, 3, 1));
    }

    @Test
    void testCiphertextLengthExceedingRealLayoutCapacityThrowsInvalidHeaderExceptionBeforeExtraction() {
        SteganoEngine engine = new SteganoEngine();
        int width = 30;
        int height = 30; // 900 pixels
        BufferedImage carrier = createCarrier(width, height);
        long poolPixels = (width * height) - SequentialLayout.HEADER_PIXELS; // 766 pixels
        long depth1CapacityBytes = (poolPixels * 3L) / 8L; // 287 bytes
        long theoreticalMaxBytes = (poolPixels * 9L) / 8L; // 861 bytes

        // Ciphertext length 500 bytes: passes theoretical max (861 bytes) but exceeds depth-1 capacity (287 bytes)
        long excessiveCiphertextLength = 500L;

        byte[] salt = new byte[16];
        byte[] nonce = new byte[12];
        random.nextBytes(salt);
        random.nextBytes(nonce);

        byte[] headerBytes = new byte[EnvelopeHeader.HEADER_SIZE_BYTES];
        ByteBuffer bb = ByteBuffer.wrap(headerBytes).order(ByteOrder.BIG_ENDIAN);
        bb.put(EnvelopeHeader.MAGIC);
        bb.put(EnvelopeHeader.CIPHER_ID_AES_256_GCM);
        bb.put(EnvelopeHeader.KDF_ID_ARGON2ID);
        bb.put((byte) 0); // Non-adaptive (depth 1)
        bb.put((byte) 0); // profileId
        bb.putInt(NyxKeyDerivation.DEFAULT_ARGON2_MEMORY_KIB);
        bb.put((byte) NyxKeyDerivation.DEFAULT_ARGON2_ITERATIONS);
        bb.put((byte) NyxKeyDerivation.DEFAULT_ARGON2_PARALLELISM);
        bb.put(salt);
        bb.put(nonce);
        bb.putInt((int) excessiveCiphertextLength);

        CRC32 crc = new CRC32();
        crc.update(headerBytes, 0, EnvelopeHeader.AAD_SIZE_BYTES);
        bb.putInt((int) crc.getValue());

        // Embed into carrier header pixels
        int[] pixelBuffer = RasterAccess.directPixels(carrier);
        int[] shifts = {16, 8, 0};
        int bitIdx = 0;
        for (int p = 0; p < SequentialLayout.HEADER_PIXELS; p++) {
            int pixel = pixelBuffer[p];
            for (int shift : shifts) {
                if (bitIdx >= 400) break;
                int bit = (headerBytes[bitIdx / 8] >>> (7 - (bitIdx % 8))) & 1;
                pixel = (pixel & ~(1 << shift)) | (bit << shift);
                bitIdx++;
            }
            pixelBuffer[p] = pixel;
            if (bitIdx >= 400) break;
        }

        InvalidHeaderException ex = assertThrows(
                InvalidHeaderException.class,
                () -> engine.reveal(carrier, "AnyPass".toCharArray())
        );
        assertTrue(ex.getMessage().contains("exceeds real carrier capacity"));
    }

    @Test
    void testArgon2MemorySafetyCheckThrowsInsufficientMemoryException() {
        SteganoEngine engine = new SteganoEngine();
        int width = 30;
        int height = 30;
        BufferedImage carrier = createCarrier(width, height);

        // Memory size 1048576 KiB = 1024 MiB (valid header limit, but exceeds heap if maxMemory < 1024 MB + 64 MB margin)
        long excessiveMemoryKiB = (Runtime.getRuntime().maxMemory() / 1024L) + 1024L;
        // Direct unit check on checkArgon2MemorySafety
        assertThrows(InsufficientMemoryException.class, () ->
                SteganoEngine.checkArgon2MemorySafety(excessiveMemoryKiB));

        // Test with header deserialization and reveal if within header's MAX_ARGON2_MEMORY_KIB (1048576 KiB)
        long testMemKiB = Math.min(EnvelopeHeader.MAX_ARGON2_MEMORY_KIB, excessiveMemoryKiB);
        if (testMemKiB * 1024L + SteganoEngine.ARGON2_MEMORY_SAFETY_MARGIN_BYTES > Runtime.getRuntime().maxMemory()) {
            byte[] headerBytes = new byte[EnvelopeHeader.HEADER_SIZE_BYTES];
            ByteBuffer bb = ByteBuffer.wrap(headerBytes).order(ByteOrder.BIG_ENDIAN);
            bb.put(EnvelopeHeader.MAGIC);
            bb.put(EnvelopeHeader.CIPHER_ID_AES_256_GCM);
            bb.put(EnvelopeHeader.KDF_ID_ARGON2ID);
            bb.put((byte) 0);
            bb.put((byte) 0);
            bb.putInt((int) testMemKiB);
            bb.put((byte) 1);
            bb.put((byte) 1);
            byte[] salt = new byte[16];
            byte[] nonce = new byte[12];
            random.nextBytes(salt);
            random.nextBytes(nonce);
            bb.put(salt);
            bb.put(nonce);
            bb.putInt(20);

            CRC32 crc = new CRC32();
            crc.update(headerBytes, 0, EnvelopeHeader.AAD_SIZE_BYTES);
            bb.putInt((int) crc.getValue());

            int[] pixelBuffer = RasterAccess.directPixels(carrier);
            int[] shifts = {16, 8, 0};
            int bitIdx = 0;
            for (int p = 0; p < SequentialLayout.HEADER_PIXELS; p++) {
                int pixel = pixelBuffer[p];
                for (int shift : shifts) {
                    if (bitIdx >= 400) break;
                    int bit = (headerBytes[bitIdx / 8] >>> (7 - (bitIdx % 8))) & 1;
                    pixel = (pixel & ~(1 << shift)) | (bit << shift);
                    bitIdx++;
                }
                pixelBuffer[p] = pixel;
                if (bitIdx >= 400) break;
            }

            assertThrows(InsufficientMemoryException.class, () ->
                    engine.reveal(carrier, "AnyPass".toCharArray()));
        }
    }

    @Test
    void testPlaintextFramingExhaustiveValidation() {
        byte[] fileBytes = "SecretDataContent".getBytes(StandardCharsets.UTF_8);

        // No extension
        byte[] packedEmpty = PayloadEnvelope.pack(fileBytes, "");
        PayloadEnvelope.Unpacked unpackedEmpty = PayloadEnvelope.unpack(packedEmpty);
        assertEquals("", unpackedEmpty.extension());
        assertArrayEquals(fileBytes, unpackedEmpty.fileBytes());

        // 1-char extension
        byte[] packed1Char = PayloadEnvelope.pack(fileBytes, "z");
        PayloadEnvelope.Unpacked unpacked1Char = PayloadEnvelope.unpack(packed1Char);
        assertEquals("z", unpacked1Char.extension());
        assertArrayEquals(fileBytes, unpacked1Char.fileBytes());

        // 16-char extension (maximum allowed)
        String ext16 = "1234567890abcdef";
        byte[] packed16 = PayloadEnvelope.pack(fileBytes, ext16);
        PayloadEnvelope.Unpacked unpacked16 = PayloadEnvelope.unpack(packed16);
        assertEquals(ext16, unpacked16.extension());
        assertArrayEquals(fileBytes, unpacked16.fileBytes());

        // 17-char extension (must be rejected clearly, never truncated)
        String ext17 = "1234567890abcdefg";
        IllegalArgumentException ex17 = assertThrows(IllegalArgumentException.class,
                () -> PayloadEnvelope.pack(fileBytes, ext17));
        assertTrue(ex17.getMessage().contains("^[A-Za-z0-9]{0,16}$"));

        // Non-alphanumeric extensions
        String[] invalidExts = {"a-b", "tar.gz", "a_b", "a/b", "bin$", "doc "};
        for (String inv : invalidExts) {
            assertThrows(IllegalArgumentException.class, () -> PayloadEnvelope.pack(fileBytes, inv),
                    "Expected rejection for invalid extension: " + inv);
        }

        // Uppercase extensions
        String[] upperExts = {"PDF", "ZIP", "TAR", "A1B2C3D4"};
        for (String up : upperExts) {
            byte[] packedUp = PayloadEnvelope.pack(fileBytes, up);
            PayloadEnvelope.Unpacked unpackedUp = PayloadEnvelope.unpack(packedUp);
            assertEquals(up, unpackedUp.extension());
            assertArrayEquals(fileBytes, unpackedUp.fileBytes());
        }

        // Extension extraction rule: lastDot logic
        String nameWithMultipleDots = "archive.tar.gz";
        int lastDotMulti = nameWithMultipleDots.lastIndexOf(".");
        String extMulti = (lastDotMulti == -1) ? "" : nameWithMultipleDots.substring(lastDotMulti + 1);
        assertEquals("gz", extMulti);

        String nameNoDot = "Makefile";
        int lastDotNo = nameNoDot.lastIndexOf(".");
        String extNo = (lastDotNo == -1) ? "" : nameNoDot.substring(lastDotNo + 1);
        assertEquals("", extNo);

        // Crafted unpack bounds-checking (must never throw ArrayIndexOutOfBoundsException or NegativeArraySizeException)
        assertThrows(IllegalArgumentException.class, () -> PayloadEnvelope.unpack(null));
        assertThrows(IllegalArgumentException.class, () -> PayloadEnvelope.unpack(new byte[0]));

        // extLen = 5, but total plaintext is only 4 bytes
        assertThrows(IllegalArgumentException.class, () -> PayloadEnvelope.unpack(new byte[]{5, 'p', 'd', 'f'}));

        // extLen = 17 (> 16 limit)
        byte[] crafted17 = new byte[20];
        crafted17[0] = 17;
        assertThrows(IllegalArgumentException.class, () -> PayloadEnvelope.unpack(crafted17));

        // extLen = 255 (unsigned high byte)
        byte[] crafted255 = new byte[30];
        crafted255[0] = (byte) 255;
        assertThrows(IllegalArgumentException.class, () -> PayloadEnvelope.unpack(crafted255));
    }

    private BufferedImage createGradientCarrier(int width, int height) {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        int[] pixels = RasterAccess.directPixels(img);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int r = (x * 255 / Math.max(1, width - 1)) & 0xFF;
                int g = (y * 255 / Math.max(1, height - 1)) & 0xFF;
                int b = ((x + y) * 128 / Math.max(1, width + height - 2)) & 0xFF;
                pixels[y * width + x] = (0xFF << 24) | (r << 16) | (g << 8) | b;
            }
        }
        return img;
    }

    @Test
    void testDeterminismWithFixedCryptoAndRealRandomness() {
        SteganoEngine engine = new SteganoEngine();
        int width = 50;
        int height = 50;
        byte[] payload = "DeterminismPayloadVerification123456789".getBytes(StandardCharsets.UTF_8);
        char[] password = "DeterminismSecretPassword!".toCharArray();
        byte[] salt = new byte[16];
        byte[] nonce = new byte[12];
        Arrays.fill(salt, (byte) 0x5A);
        Arrays.fill(nonce, (byte) 0x3C);

        // Fixed salt and nonce embedding produces identical stego pixels
        BufferedImage carrier1 = createGradientCarrier(width, height);
        BufferedImage carrier2 = createGradientCarrier(width, height);
        engine.cloakWithFixedCrypto(carrier1, payload, "txt", password.clone(), true, true, salt.clone(), nonce.clone());
        engine.cloakWithFixedCrypto(carrier2, payload, "txt", password.clone(), true, true, salt.clone(), nonce.clone());

        int[] pixels1 = RasterAccess.directPixels(carrier1);
        int[] pixels2 = RasterAccess.directPixels(carrier2);
        assertArrayEquals(pixels1, pixels2, "Fixed salt and nonce embedding must produce bit-identical stego pixels");

        // With real randomness, two embeddings differ in the header and in at least one payload pixel
        BufferedImage carrier3 = createGradientCarrier(width, height);
        BufferedImage carrier4 = createGradientCarrier(width, height);
        engine.cloak(carrier3, payload, "txt", password.clone(), true, true);
        engine.cloak(carrier4, payload, "txt", password.clone(), true, true);

        int[] pixels3 = RasterAccess.directPixels(carrier3);
        int[] pixels4 = RasterAccess.directPixels(carrier4);

        // Header pixels (0..133) must differ (fresh random salt and nonce)
        boolean headerDiffers = false;
        for (int i = 0; i < SequentialLayout.HEADER_PIXELS; i++) {
            if (pixels3[i] != pixels4[i]) {
                headerDiffers = true;
                break;
            }
        }
        assertTrue(headerDiffers, "Real randomness must produce different header pixels across two cloaks");

        // Payload pixels (134..N-1) must differ in at least one pixel
        boolean payloadDiffers = false;
        for (int i = SequentialLayout.HEADER_PIXELS; i < pixels3.length; i++) {
            if (pixels3[i] != pixels4[i]) {
                payloadDiffers = true;
                break;
            }
        }
        assertTrue(payloadDiffers, "Real randomness must produce different payload pixels across two cloaks");
    }
}
