package org.ghost.crypto;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;

@Deprecated
public class AESCipher extends AbstractCipher {

    private SecretKeySpec getKey(String myKey) throws Exception {
        byte[] key = myKey.getBytes(StandardCharsets.UTF_8);
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        key = sha.digest(key);
        key = Arrays.copyOf(key, 16); // 128-bit key for AES-128
        return new SecretKeySpec(key, "AES");
    }

    @Override
    public byte[] encrypt(byte[] data, String password) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, getKey(password));
        return cipher.doFinal(data);
    }

    @Override
    public byte[] decrypt(byte[] data, String password) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
        cipher.init(Cipher.DECRYPT_MODE, getKey(password));
        return cipher.doFinal(data);
    }
}