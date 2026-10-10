package org.ghost.crypto;

/**
 * Thrown when decryption fails due to invalid password or corrupted/modified carrier data.
 */
public class AuthenticationFailedException extends RuntimeException {
    public AuthenticationFailedException(String message) {
        super(message);
    }

    public AuthenticationFailedException(String message, Throwable cause) {
        super(message, cause);
    }
}
