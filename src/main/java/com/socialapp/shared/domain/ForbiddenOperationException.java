package com.socialapp.shared.domain;

/**
 * Thrown when an authenticated caller tries to act on a resource they do not own
 * (e.g. editing someone else's post). Mapped to HTTP 403 by GlobalExceptionHandler.
 * Deliberately framework-free so domain objects can throw it.
 */
public class ForbiddenOperationException extends RuntimeException {

    public ForbiddenOperationException(String message) {
        super(message);
    }
}
