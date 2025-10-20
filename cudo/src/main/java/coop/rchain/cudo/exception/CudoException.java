package coop.rchain.cudo.exception;

/**
 * Base exception for all Cudo Compute API errors.
 */
public class CudoException extends Exception {

    private final Integer statusCode;
    private final String errorCode;

    public CudoException(String message) {
        super(message);
        this.statusCode = null;
        this.errorCode = null;
    }

    public CudoException(String message, Throwable cause) {
        super(message, cause);
        this.statusCode = null;
        this.errorCode = null;
    }

    public CudoException(String message, int statusCode, String errorCode) {
        super(message);
        this.statusCode = statusCode;
        this.errorCode = errorCode;
    }

    public Integer getStatusCode() {
        return statusCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
