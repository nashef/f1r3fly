package coop.rchain.cudo.exception;

/**
 * Exception thrown when a requested resource is not found.
 */
public class ResourceNotFoundException extends CudoException {

    public ResourceNotFoundException(String resourceType, String resourceId) {
        super(String.format("%s not found: %s", resourceType, resourceId), 404, "NOT_FOUND");
    }
}
