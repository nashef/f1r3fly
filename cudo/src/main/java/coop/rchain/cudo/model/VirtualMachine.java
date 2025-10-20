package coop.rchain.cudo.model;

import com.google.gson.annotations.SerializedName;
import java.util.Map;

/**
 * Represents a Cudo Compute virtual machine.
 * Immutable.
 */
public final class VirtualMachine {

    @SerializedName("id")
    private final String id;

    @SerializedName("projectId")
    private final String projectId;

    @SerializedName("dataCenterId")
    private final String dataCenterId;

    @SerializedName("machineType")
    private final String machineType;

    @SerializedName("vmState")
    private final String vmState;

    @SerializedName("vcpus")
    private final int vcpus;

    @SerializedName("memoryGib")
    private final int memoryGib;

    @SerializedName("publicIpAddress")
    private final String publicIpAddress;

    @SerializedName("privateIpAddress")
    private final String privateIpAddress;

    @SerializedName("metadata")
    private final Map<String, String> metadata;

    @SerializedName("createTime")
    private final String createTime;

    // Constructor for Gson
    public VirtualMachine(String id, String projectId, String dataCenterId,
                         String machineType, String vmState, int vcpus, int memoryGib,
                         String publicIpAddress, String privateIpAddress,
                         Map<String, String> metadata, String createTime) {
        this.id = id;
        this.projectId = projectId;
        this.dataCenterId = dataCenterId;
        this.machineType = machineType;
        this.vmState = vmState;
        this.vcpus = vcpus;
        this.memoryGib = memoryGib;
        this.publicIpAddress = publicIpAddress;
        this.privateIpAddress = privateIpAddress;
        this.metadata = metadata;
        this.createTime = createTime;
    }

    public String getId() {
        return id;
    }

    public String getProjectId() {
        return projectId;
    }

    public String getDataCenterId() {
        return dataCenterId;
    }

    public String getMachineType() {
        return machineType;
    }

    public String getVmState() {
        return vmState;
    }

    public int getVcpus() {
        return vcpus;
    }

    public int getMemoryGib() {
        return memoryGib;
    }

    public String getPublicIpAddress() {
        return publicIpAddress;
    }

    public String getPrivateIpAddress() {
        return privateIpAddress;
    }

    public Map<String, String> getMetadata() {
        return metadata;
    }

    public String getCreateTime() {
        return createTime;
    }

    @Override
    public String toString() {
        return "VirtualMachine{" +
                "id='" + id + '\'' +
                ", projectId='" + projectId + '\'' +
                ", dataCenterId='" + dataCenterId + '\'' +
                ", vmState='" + vmState + '\'' +
                ", publicIpAddress='" + publicIpAddress + '\'' +
                '}';
    }
}
