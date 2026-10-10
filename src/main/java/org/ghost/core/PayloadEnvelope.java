package org.ghost.core;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * Handles packing and unpacking of payload metadata with strict extension validation.
 */
public final class PayloadEnvelope {

    private static final Pattern EXTENSION_PATTERN = Pattern.compile("^[A-Za-z0-9]{0,16}$");

    public record Unpacked(byte[] fileBytes, String extension) {}

    private PayloadEnvelope() {}

    public static byte[] pack(byte[] fileBytes, String extension) {
        if (fileBytes == null) {
            throw new IllegalArgumentException("fileBytes cannot be null");
        }
        if (extension == null || !EXTENSION_PATTERN.matcher(extension).matches()) {
            throw new IllegalArgumentException("Extension must match ^[A-Za-z0-9]{0,16}$, but was: " + extension);
        }
        byte[] extBytes = extension.getBytes(StandardCharsets.US_ASCII);
        byte[] packed = new byte[1 + extBytes.length + fileBytes.length];
        packed[0] = (byte) extBytes.length;
        System.arraycopy(extBytes, 0, packed, 1, extBytes.length);
        System.arraycopy(fileBytes, 0, packed, 1 + extBytes.length, fileBytes.length);
        return packed;
    }

    public static Unpacked unpack(byte[] plaintext) {
        if (plaintext == null || plaintext.length < 1) {
            throw new IllegalArgumentException("Plaintext is too short to unpack metadata");
        }
        int extLen = Byte.toUnsignedInt(plaintext[0]);
        if (extLen > 16 || plaintext.length < 1 + extLen) {
            throw new IllegalArgumentException("Invalid extension length in unpacked metadata: " + extLen);
        }
        String extension = new String(plaintext, 1, extLen, StandardCharsets.US_ASCII);
        if (!EXTENSION_PATTERN.matcher(extension).matches()) {
            throw new IllegalArgumentException("Unpacked extension must match ^[A-Za-z0-9]{0,16}$, but was: " + extension);
        }
        byte[] fileBytes = new byte[plaintext.length - 1 - extLen];
        System.arraycopy(plaintext, 1 + extLen, fileBytes, 0, fileBytes.length);
        return new Unpacked(fileBytes, extension);
    }
}
