package org.ghost.core;

/**
 * Thrown when an envelope header fails bounds checks (e.g. invalid Argon2 parameters or out-of-bounds ciphertext length).
 */
public class InvalidHeaderException extends RuntimeException {
    public InvalidHeaderException(String message) {
        super(message);
    }

    public InvalidHeaderException(String message, Throwable cause) {
        super(message, cause);
    }
}
