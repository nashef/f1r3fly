package coop.rchain.cudo.model;

import com.google.gson.annotations.SerializedName;
import java.util.Map;

/**
 * Request to create a new virtual machine.
 * Use builder pattern for construction.
 */
public final class CreateVMRequest {

    @SerializedName("id")
    private final String vmId;

    @SerializedName("dataCenterId")
    private final String dataCenterId;

    @SerializedName("machineType")
    private final String machineType;

    @SerializedName("vcpus")
    private final int vcpus;

    @SerializedName("memoryGib")
    private final int memoryGib;

    @SerializedName("bootDiskImageId")
    private final String bootDiskImageId;

    @SerializedName("bootDiskSizeGib")
    private final int bootDiskSizeGib;

    @SerializedName("sshKeySource")
    private final String sshKeySource;

    @SerializedName("metadata")
    private final Map<String, String> metadata;

    @SerializedName("ttl")
    private final Integer ttl;

    private CreateVMRequest(Builder builder) {
        this.vmId = builder.vmId;
        this.dataCenterId = builder.dataCenterId;
        this.machineType = builder.machineType;
        this.vcpus = builder.vcpus;
        this.memoryGib = builder.memoryGib;
        this.bootDiskImageId = builder.bootDiskImageId;
        this.bootDiskSizeGib = builder.bootDiskSizeGib;
        this.sshKeySource = builder.sshKeySource;
        this.metadata = builder.metadata;
        this.ttl = builder.ttl;
    }

    public String getVmId() {
        return vmId;
    }

    public String getDataCenterId() {
        return dataCenterId;
    }

    public String getMachineType() {
        return machineType;
    }

    public int getVcpus() {
        return vcpus;
    }

    public int getMemoryGib() {
        return memoryGib;
    }

    public String getBootDiskImageId() {
        return bootDiskImageId;
    }

    public int getBootDiskSizeGib() {
        return bootDiskSizeGib;
    }

    public String getSshKeySource() {
        return sshKeySource;
    }

    public Map<String, String> getMetadata() {
        return metadata;
    }

    public Integer getTtl() {
        return ttl;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String vmId;
        private String dataCenterId;
        private String machineType;
        private int vcpus = 2;
        private int memoryGib = 4;
        private String bootDiskImageId;
        private int bootDiskSizeGib = 20;
        private String sshKeySource = "NONE";
        private Map<String, String> metadata;
        private Integer ttl;

        public Builder vmId(String vmId) {
            this.vmId = vmId;
            return this;
        }

        public Builder dataCenterId(String dataCenterId) {
            this.dataCenterId = dataCenterId;
            return this;
        }

        public Builder machineType(String machineType) {
            this.machineType = machineType;
            return this;
        }

        public Builder vcpus(int vcpus) {
            this.vcpus = vcpus;
            return this;
        }

        public Builder memoryGib(int memoryGib) {
            this.memoryGib = memoryGib;
            return this;
        }

        public Builder bootDiskImageId(String imageId) {
            this.bootDiskImageId = imageId;
            return this;
        }

        public Builder bootDiskSizeGib(int sizeGib) {
            this.bootDiskSizeGib = sizeGib;
            return this;
        }

        public Builder sshKeySource(String source) {
            this.sshKeySource = source;
            return this;
        }

        public Builder metadata(Map<String, String> metadata) {
            this.metadata = metadata;
            return this;
        }

        public Builder ttl(int seconds) {
            this.ttl = seconds;
            return this;
        }

        public CreateVMRequest build() {
            if (vmId == null || vmId.isEmpty()) {
                throw new IllegalArgumentException("VM ID is required");
            }
            if (dataCenterId == null || dataCenterId.isEmpty()) {
                throw new IllegalArgumentException("Data center ID is required");
            }
            if (bootDiskImageId == null || bootDiskImageId.isEmpty()) {
                throw new IllegalArgumentException("Boot disk image ID is required");
            }
            return new CreateVMRequest(this);
        }
    }
}
