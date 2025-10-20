package coop.rchain.cudo;

import coop.rchain.cudo.exception.CudoException;
import coop.rchain.cudo.model.CreateVMRequest;
import coop.rchain.cudo.model.VirtualMachine;
import org.junit.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.Assert.*;

/**
 * Integration tests for CudoClient.
 * These tests require a valid API key and project ID set as environment variables:
 * - CUDO_API_KEY
 * - CUDO_PROJECT_ID
 * - CUDO_DATA_CENTER_ID (optional, defaults to a known data center)
 */
public class CudoClientTest {

    private static final String API_KEY = System.getenv("CUDO_API_KEY");
    private static final String PROJECT_ID = System.getenv("CUDO_PROJECT_ID");
    private static final String DATA_CENTER_ID = System.getenv().getOrDefault("CUDO_DATA_CENTER_ID", "gb-bournemouth-1");

    /**
     * Test configuration builder.
     */
    @Test
    public void testConfigBuilder() {
        CudoConfig config = CudoConfig.builder()
                .apiKey("test-key")
                .projectId("test-project")
                .build();

        assertEquals("test-key", config.getApiKey());
        assertEquals("test-project", config.getProjectId());
        assertEquals("https://rest.compute.cudo.org", config.getBaseUrl());
        assertEquals(30, config.getConnectTimeout());
    }

    /**
     * Test configuration validation.
     */
    @Test(expected = IllegalArgumentException.class)
    public void testConfigRequiresApiKey() {
        CudoConfig.builder()
                .projectId("test-project")
                .build();
    }

    /**
     * Test VM request builder.
     */
    @Test
    public void testCreateVMRequestBuilder() {
        Map<String, String> metadata = new HashMap<>();
        metadata.put("ROLE", "bootstrap");

        CreateVMRequest request = CreateVMRequest.builder()
                .vmId("test-vm-123")
                .dataCenterId("us-east-1")
                .bootDiskImageId("ubuntu-22.04")
                .machineType("standard")
                .vcpus(2)
                .memoryGib(4)
                .metadata(metadata)
                .ttl(3600)
                .build();

        assertEquals("test-vm-123", request.getVmId());
        assertEquals("us-east-1", request.getDataCenterId());
        assertEquals(2, request.getVcpus());
        assertEquals("bootstrap", request.getMetadata().get("ROLE"));
    }

    /**
     * Integration test: List VMs.
     * Requires valid API key and project ID.
     */
    @Test
    public void testListVMs() throws CudoException {
        if (API_KEY == null || PROJECT_ID == null) {
            System.out.println("Skipping integration test - CUDO_API_KEY or CUDO_PROJECT_ID not set");
            return;
        }

        CudoConfig config = CudoConfig.builder()
                .apiKey(API_KEY)
                .projectId(PROJECT_ID)
                .build();

        try (CudoClient client = new CudoClient(config)) {
            List<VirtualMachine> vms = client.listVMs();
            assertNotNull(vms);
            System.out.println("Found " + vms.size() + " VMs in project");

            for (VirtualMachine vm : vms) {
                System.out.println("  - " + vm.getId() + ": " + vm.getVmState() +
                        " (Public IP: " + vm.getPublicIpAddress() + ")");
            }
        }
    }

    /**
     * Integration test: Create and terminate VM.
     * WARNING: This test will create and delete a real VM (costs money!).
     * Only run if you're ready to test with real infrastructure.
     */
    // @Test
    public void testCreateAndTerminateVM() throws CudoException, InterruptedException {
        if (API_KEY == null || PROJECT_ID == null) {
            System.out.println("Skipping integration test - CUDO_API_KEY or CUDO_PROJECT_ID not set");
            return;
        }

        CudoConfig config = CudoConfig.builder()
                .apiKey(API_KEY)
                .projectId(PROJECT_ID)
                .build();

        String vmId = "test-vm-" + System.currentTimeMillis();

        Map<String, String> metadata = new HashMap<>();
        metadata.put("ROLE", "bootstrap");
        metadata.put("TEST", "true");

        CreateVMRequest request = CreateVMRequest.builder()
                .vmId(vmId)
                .dataCenterId(DATA_CENTER_ID)
                .machineType("standard")
                .vcpus(2)
                .memoryGib(4)
                .bootDiskImageId("ubuntu-22.04-lts") // Base Ubuntu image
                .bootDiskSizeGib(20)
                .sshKeySource("ACCOUNT")
                .metadata(metadata)
                .ttl(600) // 10 minutes TTL for safety
                .build();

        try (CudoClient client = new CudoClient(config)) {
            System.out.println("Creating VM: " + vmId);
            VirtualMachine vm = client.createVM(request);
            assertNotNull(vm);
            assertEquals(vmId, vm.getId());
            System.out.println("VM created: " + vm.getId() + " (state: " + vm.getVmState() + ")");

            // Wait for public IP
            System.out.println("Waiting for public IP (max 120 seconds)...");
            Optional<String> publicIp = client.waitForPublicIP(vmId, 120);

            if (publicIp.isPresent()) {
                System.out.println("Public IP assigned: " + publicIp.get());
            } else {
                System.out.println("Public IP not assigned within timeout");
            }

            // Verify we can fetch the VM
            Optional<VirtualMachine> fetchedVm = client.getVM(vmId);
            assertTrue("VM should exist", fetchedVm.isPresent());
            assertEquals(vmId, fetchedVm.get().getId());

            // Terminate VM
            System.out.println("Terminating VM...");
            client.terminateVM(vmId);
            System.out.println("VM terminated successfully");

        } catch (CudoException e) {
            System.err.println("Test failed: " + e.getMessage());
            if (e.getStatusCode() != null) {
                System.err.println("Status code: " + e.getStatusCode());
            }
            throw e;
        }
    }
}
