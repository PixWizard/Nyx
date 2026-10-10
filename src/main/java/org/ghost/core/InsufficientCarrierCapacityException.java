package org.ghost.core;

/**
 * Thrown when a carrier image does not have sufficient capacity to store the required payload.
 */
public class InsufficientCarrierCapacityException extends RuntimeException {
    private final long requiredBytes;
    private final long availableBytes;

    public InsufficientCarrierCapacityException(long requiredBytes, long availableBytes) {
        super(String.format("Carrier image capacity exceeded: required %d bytes, but available capacity is %d bytes",
                requiredBytes, availableBytes));
        this.requiredBytes = requiredBytes;
        this.availableBytes = availableBytes;
    }

    public long getRequiredBytes() {
        return requiredBytes;
    }

    public long getAvailableBytes() {
        return availableBytes;
    }
}
