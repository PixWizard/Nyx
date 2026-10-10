package org.ghost.crypto;

import org.bouncycastle.crypto.PasswordConverter;
import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;

/**
 * Key derivation hierarchy implementing Argon2id password hashing and HMAC-SHA256 subkey derivation.
 */
public final class NyxKeyDerivation {

    public static final int DEFAULT_ARGON2_MEMORY_KIB = 65536; // 64 MiB
    public static final int DEFAULT_ARGON2_ITERATIONS = 3;
    public static final int DEFAULT_ARGON2_PARALLELISM = 1;

    public static final int SALT_LENGTH_BYTES = 16;
    public static final int NONCE_LENGTH_BYTES = 12;
    public static final int KEY_LENGTH_BYTES = 32;

    private static final byte[] AES_INFO;
    private static final byte[] SCATTER_INFO;

    static {
        byte[] aesBase = "nyx/aes-256-gcm".getBytes(StandardCharsets.US_ASCII);
        AES_INFO = new byte[aesBase.length + 1];
        System.arraycopy(aesBase, 0, AES_INFO, 0, aesBase.length);
        AES_INFO[aesBase.length] = 0x01;

        byte[] scatterBase = "nyx/scatter".getBytes(StandardCharsets.US_ASCII);
        SCATTER_INFO = new byte[scatterBase.length + 1];
        System.arraycopy(scatterBase, 0, SCATTER_INFO, 0, scatterBase.length);
        SCATTER_INFO[scatterBase.length] = 0x01;
    }

    public record DerivedKeys(byte[] aesKey, byte[] scatterKey) implements AutoCloseable {
        @Override
        public void close() {
            if (aesKey != null) Arrays.fill(aesKey, (byte) 0);
            if (scatterKey != null) Arrays.fill(scatterKey, (byte) 0);
        }
    }

    private NyxKeyDerivation() {}

    /**
     * Derives AES-256 and scatter keys from password and salt using Argon2id and HMAC-SHA256.
     * Always zeroes masterKey and the passed password char[] after derivation.
     */
    public static DerivedKeys deriveKeys(char[] password, byte[] salt, int memoryKiB, int iterations, int parallelism) {
        if (password == null || password.length == 0) {
            throw new IllegalArgumentException("Password cannot be null or empty");
        }
        byte[] masterKey = new byte[KEY_LENGTH_BYTES];
        try {
            Argon2Parameters.Builder builder = new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                    .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                    .withMemoryAsKB(memoryKiB)
                    .withIterations(iterations)
                    .withParallelism(parallelism)
                    .withSalt(salt)
                    .withCharToByteConverter(PasswordConverter.UTF8);

            Argon2Parameters params = builder.build();
            Argon2BytesGenerator generator = new Argon2BytesGenerator();
            generator.init(params);
            generator.generateBytes(password, masterKey);

            byte[] aesKey = computeHmacSha256(masterKey, AES_INFO);
            byte[] scatterKey = computeHmacSha256(masterKey, SCATTER_INFO);

            return new DerivedKeys(aesKey, scatterKey);
        } finally {
            Arrays.fill(masterKey, (byte) 0);
            if (password != null) {
                Arrays.fill(password, (char) 0);
            }
        }
    }

    private static byte[] computeHmacSha256(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            // Note: per JDK source and Javadoc, SecretKeySpec clones the input key array into a private field
            // and does not implement Destroyable, so its internal copy persists until garbage collection.
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 algorithm unavailable", e);
        }
    }
}
