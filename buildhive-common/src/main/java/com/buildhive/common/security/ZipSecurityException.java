package com.buildhive.common.security;

/**
 * Exception thrown when an archive violates security rules (Zip Slip, Zip Bomb, forbidden binaries, symlinks).
 */
public class ZipSecurityException extends SecurityException {

    public ZipSecurityException(String message) {
        super(message);
    }

    public ZipSecurityException(String message, Throwable cause) {
        super(message, cause);
    }
}
