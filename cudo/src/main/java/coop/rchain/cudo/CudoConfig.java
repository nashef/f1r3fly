package coop.rchain.cudo;

/**
 * Configuration for Cudo Compute client.
 * Immutable and thread-safe.
 */
public final class CudoConfig {

    private final String apiKey;
    private final String projectId;
    private final String baseUrl;
    private final int connectTimeout;
    private final int readTimeout;
    private final int maxRetries;

    private CudoConfig(Builder builder) {
        this.apiKey = builder.apiKey;
        this.projectId = builder.projectId;
        this.baseUrl = builder.baseUrl;
        this.connectTimeout = builder.connectTimeout;
        this.readTimeout = builder.readTimeout;
        this.maxRetries = builder.maxRetries;
    }

    public String getApiKey() {
        return apiKey;
    }

    public String getProjectId() {
        return projectId;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public int getConnectTimeout() {
        return connectTimeout;
    }

    public int getReadTimeout() {
        return readTimeout;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String apiKey;
        private String projectId;
        private String baseUrl = "https://rest.compute.cudo.org";
        private int connectTimeout = 30; // seconds
        private int readTimeout = 60;    // seconds
        private int maxRetries = 3;

        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        public Builder projectId(String projectId) {
            this.projectId = projectId;
            return this;
        }

        public Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        public Builder connectTimeout(int seconds) {
            this.connectTimeout = seconds;
            return this;
        }

        public Builder readTimeout(int seconds) {
            this.readTimeout = seconds;
            return this;
        }

        public Builder maxRetries(int retries) {
            this.maxRetries = retries;
            return this;
        }

        public CudoConfig build() {
            if (apiKey == null || apiKey.isEmpty()) {
                throw new IllegalArgumentException("API key is required");
            }
            if (projectId == null || projectId.isEmpty()) {
                throw new IllegalArgumentException("Project ID is required");
            }
            return new CudoConfig(this);
        }
    }
}
