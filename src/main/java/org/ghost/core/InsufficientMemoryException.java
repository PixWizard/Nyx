package org.ghost.core;

/**
 * Thrown when required Argon2 memory plus safety margin exceeds JVM maximum heap capacity.
 * Tells the user to raise -Xmx rather than relying on catching OutOfMemoryError.
 */
public class InsufficientMemoryException extends InvalidHeaderException {
    public InsufficientMemoryException(String message) {
        super(message);
    }
}
