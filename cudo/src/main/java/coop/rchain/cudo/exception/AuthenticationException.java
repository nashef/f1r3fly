package coop.rchain.cudo.exception;

/**
 * Exception thrown when API authentication fails.
 */
public class AuthenticationException extends CudoException {

    public AuthenticationException(String message) {
        super(message, 401, "AUTHENTICATION_FAILED");
    }
}
