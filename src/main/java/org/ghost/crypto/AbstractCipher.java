package org.ghost.crypto;

public abstract class AbstractCipher {
    
    // Forces any child class to implement their own encryption logic
    public abstract byte[] encrypt(byte[] data, String password) throws Exception;

    // Forces any child class to implement their own decryption logic
    public abstract byte[] decrypt(byte[] data, String password) throws Exception;
}
