package org.twostack.libspiffy4j.service;

public class ArcServiceException extends RuntimeException {

    private final int httpStatusCode;
    private final String responseBody;

    public ArcServiceException(String message, int httpStatusCode, String responseBody) {
        super(message);
        this.httpStatusCode = httpStatusCode;
        this.responseBody = responseBody;
    }

    public ArcServiceException(String message, Throwable cause) {
        super(message, cause);
        this.httpStatusCode = -1;
        this.responseBody = null;
    }

    public int httpStatusCode() {
        return httpStatusCode;
    }

    public String responseBody() {
        return responseBody;
    }
}
