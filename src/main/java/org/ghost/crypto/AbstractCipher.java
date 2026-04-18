package org.ghost.crypto;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;

//Abstract base class for all cipher implementations.
//Provides shared key derivation logic, avoiding code duplication across subclasses.
public abstract class AbstractCipher implements CipherProtocol {

    //Derives a SecretKeySpec from the given password using SHA-256.
    //This is shared logic available to all subclasses via inheritance.
    protected SecretKeySpec deriveKey(String password) throws Exception {
        byte[] key = password.getBytes(StandardCharsets.UTF_8);
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        key = sha.digest(key);
        key = Arrays.copyOf(key, 16); //Use first 128 bits for AES-128.
        return new SecretKeySpec(key, "AES");
    }

    //Forces subclasses to declare which algorithm they use.
    @Override
    public abstract String getAlgorithmName();

    //Forces subclasses to provide their own encryption logic.
    @Override
    public abstract byte[] encrypt(byte[] data, String secret) throws Exception;

    //Forces subclasses to provide their own decryption logic.
    @Override
    public abstract byte[] decrypt(byte[] data, String secret) throws Exception;
}
