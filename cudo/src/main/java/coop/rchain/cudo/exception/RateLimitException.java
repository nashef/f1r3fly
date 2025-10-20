package coop.rchain.cudo.exception;

/**
 * Exception thrown when API rate limit is exceeded.
 */
public class RateLimitException extends CudoException {

    private final int retryAfterSeconds;

    public RateLimitException(int retryAfterSeconds) {
        super("Rate limit exceeded. Retry after " + retryAfterSeconds + " seconds", 429, "RATE_LIMIT");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public int getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
