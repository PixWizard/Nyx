package org.ghost.crypto;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;

/**
 * Authenticated cipher wrapping AES-256-GCM with 128-bit authentication tag.
 */
public final class AuthenticatedCipher {

    public static final int GCM_TAG_LENGTH_BITS = 128;
    public static final int GCM_TAG_LENGTH_BYTES = 16;
    public static final int GCM_NONCE_LENGTH_BYTES = 12;

    private AuthenticatedCipher() {}

    public static byte[] encrypt(byte[] plaintext, byte[] aesKey, byte[] nonce, byte[] aad) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH_BITS, nonce);
            // Note: per JDK source and Javadoc, SecretKeySpec clones the input key array into a private field
            // and does not implement Destroyable, so its internal copy persists until garbage collection.
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(aesKey, "AES"), spec);
            if (aad != null && aad.length > 0) {
                cipher.updateAAD(aad);
            }
            return cipher.doFinal(plaintext);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-GCM encryption failed", e);
        }
    }

    public static byte[] decrypt(byte[] ciphertextWithTag, byte[] aesKey, byte[] nonce, byte[] aad) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH_BITS, nonce);
            // Note: per JDK source and Javadoc, SecretKeySpec clones the input key array into a private field
            // and does not implement Destroyable, so its internal copy persists until garbage collection.
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(aesKey, "AES"), spec);
            if (aad != null && aad.length > 0) {
                cipher.updateAAD(aad);
            }
            return cipher.doFinal(ciphertextWithTag);
        } catch (AEADBadTagException e) {
            throw new AuthenticationFailedException("wrong password or the carrier was modified/recompressed", e);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-GCM decryption failed", e);
        }
    }
}
