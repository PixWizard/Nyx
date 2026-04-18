package org.ghost.crypto;

//Defines the contract that all cipher implementations must follow.
public interface CipherProtocol {

    byte[] encrypt(byte[] data, String secret) throws Exception;

    byte[] decrypt(byte[] data, String secret) throws Exception;

    //Each cipher must identify itself.
    String getAlgorithmName();
}
