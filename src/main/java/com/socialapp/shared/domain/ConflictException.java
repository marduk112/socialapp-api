package com.socialapp.shared.domain;

/**
 * Thrown when an operation conflicts with existing state (e.g. e-mail already registered,
 * duplicate reaction). Mapped to HTTP 409 by GlobalExceptionHandler.
 * Framework-free so domain and application code can throw it.
 */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
