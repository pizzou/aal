package com.logiplatform.integration.control;

/** Carries whether an outbound operation's outcome is known after transport failure. */
public class ExternalOperationException extends RuntimeException {
    private final boolean outcomeUnknown;
    private final Integer httpStatus;

    public ExternalOperationException(String message, Throwable cause, boolean outcomeUnknown, Integer httpStatus) {
        super(message, cause);
        this.outcomeUnknown = outcomeUnknown;
        this.httpStatus = httpStatus;
    }

    public boolean outcomeUnknown() { return outcomeUnknown; }
    public Integer httpStatus() { return httpStatus; }
}
