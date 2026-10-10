package org.ghost.core;

/**
 * Thrown when an image does not contain a valid Nyx payload (e.g. magic mismatch or CRC mismatch).
 */
public class NoNyxPayloadException extends RuntimeException {
    public NoNyxPayloadException(String message) {
        super(message);
    }

    public NoNyxPayloadException(String message, Throwable cause) {
        super(message, cause);
    }
}
