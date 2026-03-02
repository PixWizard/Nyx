package org.ghost.crypto;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;

public class CipherManager {
    private static SecretKeySpec getKey(String myKey) throws Exception {
        byte[] key = myKey.getBytes(StandardCharsets.UTF_8);
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        key = sha.digest(key);
        key = Arrays.copyOf(key, 16); // Use first 128 bits for AES-128.
        return new SecretKeySpec(key, "AES"); //Necessary for giving the key its identity.
    }

    // ENCRYPTION: Takes raw bytes, returns raw encrypted bytes
    public static byte[] encrypt(byte[] dataToEncrypt, String secret) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, getKey(secret));
        return cipher.doFinal(dataToEncrypt);
    }

    // DECRYPTION: Takes raw encrypted bytes, returns raw decrypted bytes
    public static byte[] decrypt(byte[] dataToDecrypt, String secret) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
        cipher.init(Cipher.DECRYPT_MODE, getKey(secret));
        return cipher.doFinal(dataToDecrypt);
    }
}