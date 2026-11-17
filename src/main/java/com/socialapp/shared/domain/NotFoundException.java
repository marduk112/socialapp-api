package com.socialapp.shared.domain;

/**
 * Thrown when a requested aggregate/resource does not exist. Mapped to HTTP 404 by GlobalExceptionHandler.
 * Framework-free so domain and application code can throw it.
 */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }
}
