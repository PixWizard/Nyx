package org.ghost.crypto;

import javax.crypto.Cipher;

//Concrete AES-ECB cipher implementation.
//Inherits key derivation logic from AbstractCipher.
//Polymorphically referred to via CipherProtocol interface in Main.
public class CipherManager extends AbstractCipher {

    @Override
    public String getAlgorithmName() {
        return "AES/ECB/PKCS5Padding";
    }

    //ENCRYPTION: Uses inherited deriveKey() — no code duplication.
    @Override
    public byte[] encrypt(byte[] data, String secret) throws Exception {
        Cipher cipher = Cipher.getInstance(getAlgorithmName());
        cipher.init(Cipher.ENCRYPT_MODE, deriveKey(secret));
        return cipher.doFinal(data);
    }

    //DECRYPTION: Uses inherited deriveKey() — no code duplication.
    @Override
    public byte[] decrypt(byte[] data, String secret) throws Exception {
        Cipher cipher = Cipher.getInstance(getAlgorithmName());
        cipher.init(Cipher.DECRYPT_MODE, deriveKey(secret));
        return cipher.doFinal(data);
    }
}