package coop.rchain.cudo;

import coop.rchain.cudo.exception.*;
import coop.rchain.cudo.model.*;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Main client for Cudo Compute REST API.
 * Thread-safe and reusable across multiple requests.
 */
public class CudoClient implements AutoCloseable {

    private final CudoConfig config;
    private final HttpClient httpClient;
    private final Gson gson;

    /**
     * Create a new Cudo client with configuration.
     *
     * @param config Client configuration including API key and project ID
     */
    public CudoClient(CudoConfig config) {
        this.config = config;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(config.getConnectTimeout()))
                .build();
        this.gson = new GsonBuilder()
                .setPrettyPrinting()
                .create();
    }

    // ===== VM Management =====

    /**
     * List all VMs in the project.
     * Maps to: GET /v1/projects/{projectId}/vms
     *
     * @return List of virtual machines
     * @throws CudoException on API errors
     */
    public List<VirtualMachine> listVMs() throws CudoException {
        String url = String.format("%s/v1/projects/%s/vms",
                config.getBaseUrl(), config.getProjectId());

        HttpRequest request = buildRequest(url, "GET", null);
        String responseBody = executeRequest(request);

        // Parse response - API returns {"vms": [...]}
        Map<String, Object> response = gson.fromJson(responseBody, new TypeToken<Map<String, Object>>(){}.getType());
        if (response.containsKey("vms")) {
            String vmsJson = gson.toJson(response.get("vms"));
            return gson.fromJson(vmsJson, new TypeToken<List<VirtualMachine>>(){}.getType());
        }
        return List.of();
    }

    /**
     * Get details of a specific VM.
     * Maps to: GET /v1/projects/{projectId}/vms/{id}
     *
     * @param vmId VM identifier
     * @return Virtual machine details, or empty if not found
     * @throws CudoException on API errors
     */
    public Optional<VirtualMachine> getVM(String vmId) throws CudoException {
        String url = String.format("%s/v1/projects/%s/vms/%s",
                config.getBaseUrl(), config.getProjectId(), vmId);

        HttpRequest request = buildRequest(url, "GET", null);

        try {
            String responseBody = executeRequest(request);
            VirtualMachine vm = gson.fromJson(responseBody, VirtualMachine.class);
            return Optional.of(vm);
        } catch (ResourceNotFoundException e) {
            return Optional.empty();
        }
    }

    /**
     * Create a new virtual machine.
     * Maps to: POST /v1/projects/{projectId}/vm
     *
     * @param vmRequest VM creation request with all parameters
     * @return Created virtual machine with assigned ID
     * @throws CudoException on API errors
     */
    public VirtualMachine createVM(CreateVMRequest vmRequest) throws CudoException {
        String url = String.format("%s/v1/projects/%s/vm",
                config.getBaseUrl(), config.getProjectId());

        String requestBody = gson.toJson(vmRequest);
        HttpRequest request = buildRequest(url, "POST", requestBody);
        String responseBody = executeRequest(request);

        return gson.fromJson(responseBody, VirtualMachine.class);
    }

    /**
     * Terminate and delete a VM.
     * Maps to: POST /v1/projects/{projectId}/vms/{id}/terminate
     *
     * @param vmId VM identifier
     * @throws CudoException on API errors
     */
    public void terminateVM(String vmId) throws CudoException {
        String url = String.format("%s/v1/projects/%s/vms/%s/terminate",
                config.getBaseUrl(), config.getProjectId(), vmId);

        HttpRequest request = buildRequest(url, "POST", null);
        executeRequest(request);
    }

    /**
     * Wait for VM to receive public IP address.
     * Polls every 5 seconds with configurable timeout.
     *
     * @param vmId VM identifier
     * @param timeoutSeconds Maximum time to wait
     * @return Public IP address, or empty if timeout
     * @throws CudoException on API errors
     */
    public Optional<String> waitForPublicIP(String vmId, int timeoutSeconds) throws CudoException {
        long deadline = System.currentTimeMillis() + (timeoutSeconds * 1000L);

        while (System.currentTimeMillis() < deadline) {
            Optional<VirtualMachine> vmOpt = getVM(vmId);

            if (vmOpt.isPresent()) {
                VirtualMachine vm = vmOpt.get();
                String publicIp = vm.getPublicIpAddress();

                if (publicIp != null && !publicIp.isEmpty()) {
                    return Optional.of(publicIp);
                }
            }

            try {
                Thread.sleep(5000); // Poll every 5 seconds
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new CudoException("Interrupted while waiting for public IP", e);
            }
        }

        return Optional.empty(); // Timeout
    }

    // ===== HTTP Layer =====

    private HttpRequest buildRequest(String url, String method, String body) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(config.getReadTimeout()))
                .header("Authorization", "Bearer " + config.getApiKey())
                .header("Accept", "application/json")
                .header("Content-Type", "application/json");

        if (body != null && !body.isEmpty()) {
            builder.method(method, HttpRequest.BodyPublishers.ofString(body));
        } else {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        }

        return builder.build();
    }

    private String executeRequest(HttpRequest request) throws CudoException {
        int attempts = 0;
        Exception lastException = null;

        while (attempts < config.getMaxRetries()) {
            try {
                HttpResponse<String> response = httpClient.send(
                        request,
                        HttpResponse.BodyHandlers.ofString()
                );

                // Handle different status codes
                int statusCode = response.statusCode();

                if (statusCode >= 200 && statusCode < 300) {
                    return response.body();
                } else if (statusCode == 401) {
                    throw new AuthenticationException("Invalid API key");
                } else if (statusCode == 404) {
                    throw new ResourceNotFoundException("Resource", "unknown");
                } else if (statusCode == 429) {
                    String retryAfter = response.headers()
                            .firstValue("Retry-After")
                            .orElse("60");
                    int retrySeconds = Integer.parseInt(retryAfter);

                    // Wait and retry for rate limits
                    Thread.sleep(retrySeconds * 1000L);
                    attempts++;
                    continue;
                } else {
                    String errorMsg = String.format("API error (status %d): %s",
                            statusCode, response.body());
                    throw new CudoException(errorMsg, statusCode, "API_ERROR");
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new CudoException("Request interrupted", e);
            } catch (IOException e) {
                attempts++;
                lastException = e;
                if (attempts < config.getMaxRetries()) {
                    try {
                        Thread.sleep(1000L * attempts); // Exponential backoff
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new CudoException("Request interrupted during retry", ie);
                    }
                }
            } catch (CudoException e) {
                // Don't retry on auth errors or other known exceptions
                throw e;
            }
        }

        throw new CudoException("Max retries exceeded", lastException);
    }

    @Override
    public void close() {
        // HttpClient doesn't need explicit cleanup
    }
}
