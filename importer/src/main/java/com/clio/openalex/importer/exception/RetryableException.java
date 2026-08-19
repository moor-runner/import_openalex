package com.clio.openalex.importer.exception;

public class RetryableException extends RuntimeException{
    public RetryableException(String message) {
        super(message);
    }

    public RetryableException() {
        super();
    }

    public RetryableException(Throwable cause) {
        super(cause);
    }

    public RetryableException(String message, Throwable cause) {
        super(message, cause);
    }
}
