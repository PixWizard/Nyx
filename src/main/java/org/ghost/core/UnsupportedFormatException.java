package org.ghost.core;

/**
 * Thrown when an envelope header specifies an unsupported cipher ID or KDF ID.
 */
public class UnsupportedFormatException extends RuntimeException {
    public UnsupportedFormatException(String message) {
        super(message);
    }

    public UnsupportedFormatException(String message, Throwable cause) {
        super(message, cause);
    }
}
