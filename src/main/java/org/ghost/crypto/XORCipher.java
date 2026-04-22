package org.ghost.crypto;

public class XORCipher extends AbstractCipher {

    @Override
    public byte[] encrypt(byte[] data, String password) {
        if (data == null || password == null || password.isEmpty()) {
            return data; 
        }

        byte[] result = new byte[data.length];
        byte[] key = password.getBytes();

        // Perform the XOR operation byte by byte
        for (int i = 0; i < data.length; i++) {
            result[i] = (byte) (data[i] ^ key[i % key.length]);
        }

        return result;
    }

    @Override
    public byte[] decrypt(byte[] data, String password) {
        // The beauty of XOR is that the operation is symmetric.
        // Running XOR again with the same key reverses the process.
        return encrypt(data, password);
    }
}