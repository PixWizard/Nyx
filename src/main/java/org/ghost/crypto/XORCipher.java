package org.ghost.crypto;

@Deprecated
public class XORCipher extends AbstractCipher {

    @Override
    public byte[] encrypt(byte[] data, String password) {
        if (data == null || password == null || password.isEmpty()) {
            return data; 
        }

        byte[] result = new byte[data.length];
        byte[] key = password.getBytes();

        for (int i = 0; i < data.length; i++) {
            result[i] = (byte) (data[i] ^ key[i % key.length]);
        }

        return result;
    }

    @Override
    public byte[] decrypt(byte[] data, String password) {
        // Symmetric cipher: encryption and decryption are identical operations.
        return encrypt(data, password);
    }
}