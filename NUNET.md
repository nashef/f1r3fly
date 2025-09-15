# NuNet Integration for F1R3FLY

This document describes how to use the NuNet Device Management Service (DMS) integration with F1R3FLY's RChain blockchain platform.

## Overview

The NuNet integration allows Rholang smart contracts to interact with a locally-running NuNet Device Management Service (DMS) for decentralized compute orchestration. This integration follows the same pattern as the existing OpenAI and Ollama integrations but connects to your local NuNet DMS for distributed computing capabilities.

## Prerequisites

### 1. Install and Start NuNet DMS

```bash
# Download and install NuNet DMS from the official repository
# See: https://gitlab.com/nunet/device-management-service

# Example installation (adjust based on your platform):
wget https://github.com/nunet-io/device-management-service/releases/latest/download/nunet-dms-linux-amd64
chmod +x nunet-dms-linux-amd64
sudo mv nunet-dms-linux-amd64 /usr/local/bin/nunet

# Start the Device Management Service
nunet run

# In another terminal, verify the service is running
curl http://localhost:9999/actor/handle
```

### 2. Build F1R3FLY with NuNet Support

```bash
# Build the project
sbt stage
```

## Configuration

### Environment Variable (Recommended)

```bash
# Enable NuNet integration
export NUNET_ENABLED=true

# Optional: Disable connection validation if needed
export NUNET_VALIDATE_CONNECTION=false
```

### Configuration File

Alternatively, you can configure via `application.conf`:

```hocon
nunet {
  enabled = true
  dms-url = "http://localhost:9999"
  actor-did = ""
  validate-connection = true
  timeout-sec = 30
}
```

## Running F1R3FLY with NuNet

### Interactive Mode (REPL)

```bash
# Set environment variable to enable NuNet
export NUNET_ENABLED=true

# Run the staged rnode binary in interactive mode
./node/target/universal/stage/bin/rnode run --standalone --repl
```

### Production Mode

```bash
# Enable NuNet and run normally
export NUNET_ENABLED=true
./node/target/universal/stage/bin/rnode run --standalone
```

## Rholang Usage Examples

### Deploy a Compute Job

Use the `rho:nunet:deploy` system process to deploy arbitrary compute jobs:

```rholang
new nunetDeploy(`rho:nunet:deploy`),
    deployResult,
    stdout(`rho:io:stdout`) in {

  // Deploy a simple job
  nunetDeploy!("{
    \"image\": \"ubuntu:latest\",
    \"command\": [\"echo\", \"Hello from NuNet!\"],
    \"resources\": {\"cpu\": \"1\", \"memory\": \"1Gi\"}
  }", *deployResult) |

  for(@jobId <- deployResult) {
    stdout!(["Job deployed with ID:", jobId])
  }
}
```

### Check Job Status

Use the `rho:nunet:status` system process to monitor job execution:

```rholang
new nunetStatus(`rho:nunet:status`),
    statusResult,
    stdout(`rho:io:stdout`) in {

  nunetStatus!("job-id-12345", *statusResult) |
  for(@status <- statusResult) {
    stdout!(["Job status:", status])
  }
}
```

### List Available Nodes

Use the `rho:nunet:nodes` system process to discover compute resources:

```rholang
new nunetNodes(`rho:nunet:nodes`),
    nodesResult,
    stdout(`rho:io:stdout`) in {

  nunetNodes!(*nodesResult) |
  for(@nodes <- nodesResult) {
    stdout!(["Available compute nodes:", nodes])
  }
}
```

### Deploy RNode Instance

Use the `rho:nunet:deploy-rnode` system process to deploy F1R3FLY nodes on NuNet:

```rholang
new nunetDeployRNode(`rho:nunet:deploy-rnode`),
    deployResult,
    stdout(`rho:io:stdout`) in {

  // Deploy RNode with default configuration
  nunetDeployRNode!(*deployResult) |
  for(@jobId <- deployResult) {
    stdout!(["RNode deployed with job ID:", jobId])
  }
}
```

### Deploy RNode with Custom Configuration

```rholang
new nunetDeployRNode(`rho:nunet:deploy-rnode`),
    deployResult,
    stdout(`rho:io:stdout`) in {

  // Deploy RNode with custom configuration
  nunetDeployRNode!("--network testnet --validator-private-key mykey.pem", *deployResult) |
  for(@jobId <- deployResult) {
    stdout!(["Custom RNode deployed with job ID:", jobId])
  }
}
```

## System Processes Reference

The NuNet integration provides four system processes:

| Process | Channel | Parameters | Description |
|---------|---------|------------|-------------|
| Deploy | `rho:nunet:deploy` | `(jobSpec, ack)` | Deploy arbitrary compute job to DMS |
| Status | `rho:nunet:status` | `(jobId, ack)` | Check status of deployed job |
| Nodes | `rho:nunet:nodes` | `(ack)` | List available compute nodes |
| Deploy RNode | `rho:nunet:deploy-rnode` | `(config, ack)` or `(ack)` | Deploy F1R3FLY RNode on DMS |

### Job Specification Format

The `rho:nunet:deploy` process expects a JSON string with the following structure:

```json
{
  "image": "docker-image:tag",
  "command": ["command", "arg1", "arg2"],
  "resources": {
    "cpu": "2",
    "memory": "4Gi",
    "storage": "10Gi"
  },
  "network": {
    "ports": [8080, 9090]
  },
  "environment": {
    "ENV_VAR": "value"
  }
}
```

## Advanced Use Cases

### Deploy a Complete F1R3FLY Shard

```rholang
new nunetDeploy(`rho:nunet:deploy`),
    nunetStatus(`rho:nunet:status`),
    deployResults, statusResults,
    stdout(`rho:io:stdout`) in {

  // Deploy bootstrap node
  nunetDeploy!("{
    \"image\": \"f1r3fly/rnode:latest\",
    \"command\": [\"./rnode\", \"run\", \"--standalone\", \"--bootstrap\"],
    \"resources\": {\"cpu\": \"2\", \"memory\": \"4Gi\"},
    \"network\": {\"ports\": [40400, 50505, 9095]}
  }", *deployResults) |

  for(@bootstrapJobId <- deployResults) {
    stdout!(["Bootstrap node deployed:", bootstrapJobId]) |

    // Deploy validator nodes
    nunetDeploy!("{
      \"image\": \"f1r3fly/rnode:latest\",
      \"command\": [\"./rnode\", \"run\", \"--validator-private-key\", \"/keys/validator1.pem\"],
      \"resources\": {\"cpu\": \"3\", \"memory\": \"6Gi\"}
    }", *deployResults) |

    for(@validatorJobId <- deployResults) {
      stdout!(["Validator node deployed:", validatorJobId])
    }
  }
}
```

### Monitor Job Execution

```rholang
new nunetStatus(`rho:nunet:status`),
    statusResult, timerCh,
    stdout(`rho:io:stdout`) in {

  // Polling function
  contract poll(@jobId) = {
    nunetStatus!(jobId, *statusResult) |
    for(@status <- statusResult) {
      stdout!(["Current status:", status]) |
      // Continue polling if job is still running
      match status {
        "running" => {
          // Wait and poll again (simplified timer)
          poll!(jobId)
        }
        "completed" => {
          stdout!(["Job completed successfully!"])
        }
        "failed" => {
          stdout!(["Job failed!"])
        }
      }
    }
  } |

  // Start polling
  poll!("your-job-id-here")
}
```

## Testing

### Unit Tests

Run the NuNet-specific tests:

```bash
sbt "rholang/testOnly *NuNetServiceSpec"
```

### Integration Testing

1. Start NuNet DMS locally
2. Enable NuNet in F1R3FLY
3. Run the REPL examples above
4. Verify responses are returned

## Troubleshooting

### Connection Issues

If you see connection errors:

1. Verify NuNet DMS is running: `curl http://localhost:9999/actor/handle`
2. Check DMS logs for any issues
3. Disable connection validation: `export NUNET_VALIDATE_CONNECTION=false`

### Configuration Issues

- Ensure `NUNET_ENABLED=true` is set
- Check DMS is running on the configured port (default: 9999)
- Verify actor DID configuration if using custom DIDs

### Job Deployment Issues

- Ensure job specifications are valid JSON
- Check resource limits don't exceed available capacity
- Verify Docker images are accessible to the DMS
- Review DMS logs for execution errors

## Security Notes

- The NuNet integration only connects to localhost by default
- Actor DIDs provide capability-based security
- All job execution happens within the NuNet security model
- Review NuNet's security best practices for production deployments

## Architecture

The integration follows F1R3FLY's system process pattern:

1. **Service Layer**: `NuNetService` trait with implementations
2. **System Processes**: Fixed channels for Rholang interaction
3. **Configuration**: Environment/config driven enablement
4. **Runtime Integration**: Wired through the entire RhoRuntime stack

This design ensures consistency with other F1R3FLY integrations and maintains the platform's security and reliability standards.

## Future Enhancements

- **Multi-DMS Support**: Connect to multiple DMS instances
- **Resource Monitoring**: Real-time compute resource tracking
- **Job Scheduling**: Advanced job placement and scheduling
- **Cost Optimization**: Economic optimization for compute allocation
- **Federation**: Cross-platform compute federation

## References

- [NuNet Documentation](https://docs.nunet.io/)
- [NuNet Device Management Service](https://gitlab.com/nunet/device-management-service)
- [F1R3FLY Integration Plan](./NUNET_INTEGRATION.md)
- [F1R3FLY Architecture](./CLAUDE_SUMMARY.md)