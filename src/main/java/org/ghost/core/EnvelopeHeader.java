package org.ghost.core;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.zip.CRC32;

/**
 * Encapsulates the 50-byte authenticated envelope header for Nyx steganography.
 */
public class EnvelopeHeader {

    public static final int OFFSET_MAGIC = 0;
    public static final int SIZE_MAGIC = 4;
    public static final byte[] MAGIC = new byte[]{0x4E, 0x59, 0x58, 0x01};

    public static final int OFFSET_CIPHER_ID = 4;
    public static final int SIZE_CIPHER_ID = 1;
    public static final byte CIPHER_ID_AES_256_GCM = 0x01;

    public static final int OFFSET_KDF_ID = 5;
    public static final int SIZE_KDF_ID = 1;
    public static final byte KDF_ID_ARGON2ID = 0x01;

    public static final int OFFSET_FLAGS = 6;
    public static final int SIZE_FLAGS = 1;
    public static final byte FLAG_SCATTERED = 0x01;
    public static final byte FLAG_ADAPTIVE = 0x02;

    public static final int OFFSET_PROFILE_ID = 7;
    public static final int SIZE_PROFILE_ID = 1;

    public static final int OFFSET_ARGON2_MEMORY = 8;
    public static final int SIZE_ARGON2_MEMORY = 4;

    public static final int OFFSET_ARGON2_ITERATIONS = 12;
    public static final int SIZE_ARGON2_ITERATIONS = 1;

    public static final int OFFSET_ARGON2_PARALLELISM = 13;
    public static final int SIZE_ARGON2_PARALLELISM = 1;

    public static final int OFFSET_SALT = 14;
    public static final int SIZE_SALT = 16;

    public static final int OFFSET_NONCE = 30;
    public static final int SIZE_NONCE = 12;

    public static final int OFFSET_CIPHERTEXT_LENGTH = 42;
    public static final int SIZE_CIPHERTEXT_LENGTH = 4;

    public static final int OFFSET_CRC32 = 46;
    public static final int SIZE_CRC32 = 4;

    public static final int HEADER_SIZE_BYTES = 50;
    public static final int AAD_SIZE_BYTES = 46;

    // Bounds for Argon2 and ciphertext validation
    public static final long MIN_ARGON2_MEMORY_KIB = 8192L;
    public static final long MAX_ARGON2_MEMORY_KIB = 1048576L;
    public static final int MIN_ARGON2_ITERATIONS = 1;
    public static final int MAX_ARGON2_ITERATIONS = 10;
    public static final int MIN_ARGON2_PARALLELISM = 1;
    public static final int MAX_ARGON2_PARALLELISM = 8;
    public static final long MIN_CIPHERTEXT_LENGTH = 16L;
    public static final long MAX_CIPHERTEXT_LENGTH = Integer.MAX_VALUE - 8;

    private final byte cipherId;
    private final byte kdfId;
    private final byte flags;
    private final byte profileId;
    private final int argon2MemoryKiB;
    private final int argon2Iterations;
    private final int argon2Parallelism;
    private final byte[] salt;
    private final byte[] nonce;
    private final long ciphertextLength;

    public EnvelopeHeader(byte cipherId, byte kdfId, byte flags, byte profileId,
                          int argon2MemoryKiB, int argon2Iterations, int argon2Parallelism,
                          byte[] salt, byte[] nonce, long ciphertextLength) {
        if (salt == null || salt.length != SIZE_SALT) {
            throw new IllegalArgumentException("Salt must be exactly " + SIZE_SALT + " bytes");
        }
        if (nonce == null || nonce.length != SIZE_NONCE) {
            throw new IllegalArgumentException("Nonce must be exactly " + SIZE_NONCE + " bytes");
        }
        this.cipherId = cipherId;
        this.kdfId = kdfId;
        this.flags = flags;
        this.profileId = profileId;
        this.argon2MemoryKiB = argon2MemoryKiB;
        this.argon2Iterations = argon2Iterations;
        this.argon2Parallelism = argon2Parallelism;
        this.salt = Arrays.copyOf(salt, SIZE_SALT);
        this.nonce = Arrays.copyOf(nonce, SIZE_NONCE);
        this.ciphertextLength = ciphertextLength;
    }

    public byte[] serialize() {
        byte[] buffer = new byte[HEADER_SIZE_BYTES];
        ByteBuffer bb = ByteBuffer.wrap(buffer).order(ByteOrder.BIG_ENDIAN);

        bb.put(OFFSET_MAGIC, MAGIC[0]);
        bb.put(OFFSET_MAGIC + 1, MAGIC[1]);
        bb.put(OFFSET_MAGIC + 2, MAGIC[2]);
        bb.put(OFFSET_MAGIC + 3, MAGIC[3]);

        bb.put(OFFSET_CIPHER_ID, cipherId);
        bb.put(OFFSET_KDF_ID, kdfId);
        bb.put(OFFSET_FLAGS, flags);
        bb.put(OFFSET_PROFILE_ID, profileId);

        bb.putInt(OFFSET_ARGON2_MEMORY, argon2MemoryKiB);
        bb.put(OFFSET_ARGON2_ITERATIONS, (byte) argon2Iterations);
        bb.put(OFFSET_ARGON2_PARALLELISM, (byte) argon2Parallelism);

        System.arraycopy(salt, 0, buffer, OFFSET_SALT, SIZE_SALT);
        System.arraycopy(nonce, 0, buffer, OFFSET_NONCE, SIZE_NONCE);

        bb.putInt(OFFSET_CIPHERTEXT_LENGTH, (int) (ciphertextLength & 0xFFFFFFFFL));

        CRC32 crc = new CRC32();
        crc.update(buffer, 0, AAD_SIZE_BYTES);
        long crcVal = crc.getValue();
        bb.putInt(OFFSET_CRC32, (int) (crcVal & 0xFFFFFFFFL));

        return buffer;
    }

    public static EnvelopeHeader deserialize(byte[] rawHeader, long maxCarrierPayloadCapacityBytes) {
        if (rawHeader == null || rawHeader.length < HEADER_SIZE_BYTES) {
            throw new NoNyxPayloadException("Header too short or null: cannot be a Nyx payload");
        }

        // Magic identifier check
        if (rawHeader[OFFSET_MAGIC] != MAGIC[0] ||
                rawHeader[OFFSET_MAGIC + 1] != MAGIC[1] ||
                rawHeader[OFFSET_MAGIC + 2] != MAGIC[2] ||
                rawHeader[OFFSET_MAGIC + 3] != MAGIC[3]) {
            throw new NoNyxPayloadException("Magic mismatch: expected NYX\\x01");
        }

        // Header CRC-32 integrity check
        CRC32 crc = new CRC32();
        crc.update(rawHeader, 0, AAD_SIZE_BYTES);
        long computedCrc = crc.getValue();

        ByteBuffer bb = ByteBuffer.wrap(rawHeader).order(ByteOrder.BIG_ENDIAN);
        long storedCrc = Integer.toUnsignedLong(bb.getInt(OFFSET_CRC32));

        if (computedCrc != storedCrc) {
            throw new NoNyxPayloadException("CRC32 mismatch: expected " + storedCrc + " but computed " + computedCrc);
        }

        // Cipher and KDF identifiers validation
        byte cipherId = rawHeader[OFFSET_CIPHER_ID];
        byte kdfId = rawHeader[OFFSET_KDF_ID];
        if (cipherId != CIPHER_ID_AES_256_GCM || kdfId != KDF_ID_ARGON2ID) {
            throw new UnsupportedFormatException("Unknown cipherId (0x" + Integer.toHexString(cipherId & 0xFF) +
                    ") or kdfId (0x" + Integer.toHexString(kdfId & 0xFF) + ")");
        }

        byte flags = rawHeader[OFFSET_FLAGS];
        byte profileId = rawHeader[OFFSET_PROFILE_ID];

        // Adaptive depth profile validation
        if ((flags & FLAG_ADAPTIVE) != 0) {
            if (profileId != DepthProfile.DEFAULT_PROFILE_ID) {
                throw new UnsupportedFormatException("Unknown depth profile id: " + profileId);
            }
        }

        // Argon2 parameters validation
        long memoryKiB = Integer.toUnsignedLong(bb.getInt(OFFSET_ARGON2_MEMORY));
        int iterations = Byte.toUnsignedInt(rawHeader[OFFSET_ARGON2_ITERATIONS]);
        int parallelism = Byte.toUnsignedInt(rawHeader[OFFSET_ARGON2_PARALLELISM]);

        if (memoryKiB < MIN_ARGON2_MEMORY_KIB || memoryKiB > MAX_ARGON2_MEMORY_KIB ||
                iterations < MIN_ARGON2_ITERATIONS || iterations > MAX_ARGON2_ITERATIONS ||
                parallelism < MIN_ARGON2_PARALLELISM || parallelism > MAX_ARGON2_PARALLELISM) {
            throw new InvalidHeaderException(String.format(
                    "Argon2 parameters outside acceptable limits: memory=%d KiB (allowed %d..%d), iterations=%d (allowed %d..%d), parallelism=%d (allowed %d..%d)",
                    memoryKiB, MIN_ARGON2_MEMORY_KIB, MAX_ARGON2_MEMORY_KIB,
                    iterations, MIN_ARGON2_ITERATIONS, MAX_ARGON2_ITERATIONS,
                    parallelism, MIN_ARGON2_PARALLELISM, MAX_ARGON2_PARALLELISM));
        }

        // Ciphertext length validation
        long ciphertextLength = Integer.toUnsignedLong(bb.getInt(OFFSET_CIPHERTEXT_LENGTH));
        if (ciphertextLength < MIN_CIPHERTEXT_LENGTH || ciphertextLength > maxCarrierPayloadCapacityBytes || ciphertextLength > MAX_CIPHERTEXT_LENGTH) {
            throw new InvalidHeaderException(String.format(
                    "Ciphertext length invalid: %d bytes (allowed minimum %d bytes, carrier payload capacity %d bytes)",
                    ciphertextLength, MIN_CIPHERTEXT_LENGTH, maxCarrierPayloadCapacityBytes));
        }

        byte[] salt = Arrays.copyOfRange(rawHeader, OFFSET_SALT, OFFSET_SALT + SIZE_SALT);
        byte[] nonce = Arrays.copyOfRange(rawHeader, OFFSET_NONCE, OFFSET_NONCE + SIZE_NONCE);

        return new EnvelopeHeader(cipherId, kdfId, flags, profileId,
                (int) memoryKiB, iterations, parallelism, salt, nonce, ciphertextLength);
    }

    public byte getCipherId() {
        return cipherId;
    }

    public byte getKdfId() {
        return kdfId;
    }

    public byte getFlags() {
        return flags;
    }

    public byte getProfileId() {
        return profileId;
    }

    public int getArgon2MemoryKiB() {
        return argon2MemoryKiB;
    }

    public int getArgon2Iterations() {
        return argon2Iterations;
    }

    public int getArgon2Parallelism() {
        return argon2Parallelism;
    }

    public byte[] getSalt() {
        return Arrays.copyOf(salt, salt.length);
    }

    public byte[] getNonce() {
        return Arrays.copyOf(nonce, nonce.length);
    }

    public long getCiphertextLength() {
        return ciphertextLength;
    }
}
