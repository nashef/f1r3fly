# NuNet Integration Plan for F1R3FLY

## Overview

This document outlines the integration of NuNet's Device Management Service (DMS) into F1R3FLY, enabling decentralized compute orchestration from Rholang smart contracts. The integration follows the same architectural pattern as the recent Ollama integration, ensuring consistency with existing F1R3FLY system processes.

## Goals

### Primary Goal
- **Launch DMS on laptop**: Start NuNet Device Management Service locally
- **Launch RNode inside DMS**: Deploy an F1R3FLY RNode instance within the DMS
- **Deploy Rholang program**: Create a smart contract that launches a new RNode on the DMS

### Stretch Goal
- **Launch entire shard**: Deploy a complete F1R3FLY shard (multiple validator nodes) on the DMS

## NuNet Overview

NuNet is a decentralized computing platform that provides:
- **Device Management Service (DMS)**: Core orchestration service
- **Actor-based Architecture**: Secure message passing between components
- **Capability-based Security**: Fine-grained access control using DIDs
- **REST API**: HTTP endpoints for job deployment and management
- **Peer-to-peer Network**: Distributed compute resource discovery

### Key NuNet Components
- `cmd`: CLI interface (`nunet run`, `nunet actor`, etc.)
- `api`: REST endpoints (`/actor/handle`, `/actor/send`, `/actor/invoke`)
- `dms`: Core functionality (onboarding, job management)
- `executor`: Job execution across different environments

## Architecture

The integration follows F1R3FLY's system process pattern:

```
Rholang Contract
    ↓
System Process (rho:nunet:deploy)
    ↓
NuNetService Implementation
    ↓
NuNet DMS REST API
    ↓
Compute Job Execution
```

## Implementation Plan

### Phase 1: Service Layer

#### 1.1 NuNetService Interface
Create `rholang/src/main/scala/coop/rchain/rholang/interpreter/NuNetService.scala`:

```scala
trait NuNetService {
  def deployJob[F[_]](jobSpec: String): F[String]        // Deploy compute job
  def getJobStatus[F[_]](jobId: String): F[String]       // Check job status
  def listAvailableNodes[F[_]](): F[List[String]]        // List compute nodes
  def deployRNode[F[_]](config: String): F[String]       // Deploy RNode
}
```

#### 1.2 Configuration Model
Add to `node/src/main/scala/coop/rchain/node/configuration/model.scala`:

```scala
final case class NuNetConf(
    enabled: Boolean,
    dmsUrl: String,
    actorDid: String,
    validateConnection: Boolean,
    timeoutSec: Int
)
```

#### 1.3 Configuration Defaults
Add to `node/src/main/resources/defaults.conf`:

```hocon
nunet {
  enabled = false
  dms-url = "http://localhost:9999"
  actor-did = ""
  validate-connection = true
  timeout-sec = 30
}
```

### Phase 2: System Processes

#### 2.1 System Process Definitions
Add to `SystemProcesses.scala`:

```scala
// Fixed channels
val NUNET_DEPLOY: Par = byteName(30)
val NUNET_STATUS: Par = byteName(31)
val NUNET_NODES: Par = byteName(32)
val NUNET_DEPLOY_RNODE: Par = byteName(33)

// Body refs
val NUNET_DEPLOY: Long = 30L
val NUNET_STATUS: Long = 31L
val NUNET_NODES: Long = 32L
val NUNET_DEPLOY_RNODE: Long = 33L
```

#### 2.2 System Process Contracts
```scala
def nunetDeploy: Contract[F]        // rho:nunet:deploy
def nunetStatus: Contract[F]        // rho:nunet:status
def nunetNodes: Contract[F]         // rho:nunet:nodes
def nunetDeployRNode: Contract[F]   // rho:nunet:deploy-rnode
```

### Phase 3: Runtime Integration

#### 3.1 RhoRuntime Updates
- Add `isNuNetEnabled` configuration check
- Create `stdRhoNuNetProcesses[F]` definition sequence
- Wire NuNetService through ProcessContext
- Update dispatch table creation

#### 3.2 Service Initialization
- Update `Setup.scala` to initialize `NuNetServiceImpl.instance`
- Update `RuntimeManager.scala` for runtime/replay contexts
- Update `ReportingCasper.scala` for reporting runtime

### Phase 4: Demo Implementation

#### 4.1 Basic Demo Script
`rholang/examples/demo-nunet-rnode.rho`:
```rholang
new nunetDeploy(`rho:nunet:deploy`),
    nunetStatus(`rho:nunet:status`),
    deployResult, statusResult,
    stdout(`rho:io:stdout`) in {

  // Deploy RNode on NuNet DMS
  nunetDeploy!({
    "image": "f1r3fly/rnode:latest",
    "command": ["./rnode", "run", "--standalone"],
    "resources": {"cpu": "2", "memory": "4Gi"}
  }, *deployResult) |

  for(@jobId <- deployResult) {
    stdout!(["RNode deployment started:", jobId]) |

    // Check deployment status
    nunetStatus!(jobId, *statusResult) |
    for(@status <- statusResult) {
      stdout!(["RNode status:", status])
    }
  }
}
```

#### 4.2 Stretch Goal: Shard Demo
`rholang/examples/demo-nunet-shard.rho`:
- Deploy multiple validator nodes
- Configure bootstrap and peer connections
- Initialize genesis block and wallets

## Technical Considerations

### 1. DMS Actor Communication
NuNet uses an actor model with message envelopes:
```json
{
  "to": "actor-did",
  "from": "sender-did",
  "behavior": "deploy-job",
  "payload": {...}
}
```

### 2. Job Specification Format
Define standardized job specs for F1R3FLY components:
```json
{
  "type": "rnode",
  "image": "f1r3fly/rnode:latest",
  "command": ["./rnode", "run", "--standalone"],
  "resources": {
    "cpu": "2",
    "memory": "4Gi",
    "storage": "10Gi"
  },
  "network": {
    "ports": [40400, 50505, 9095]
  }
}
```

### 3. Asynchronous Operations
All NuNet operations are asynchronous:
- Use acknowledgment channels consistently
- Implement proper error handling
- Support job status polling

### 4. Security Alignment
- Map F1R3FLY deploy keys to NuNet DIDs
- Implement capability-based access control
- Secure inter-node communication

## Implementation Timeline

### Week 1: Core Service Layer
- [ ] Create NuNetService trait and implementations
- [ ] Add configuration model and defaults
- [ ] Implement HTTP client for DMS API
- [ ] Basic connectivity testing

### Week 1-2: System Process Integration
- [ ] Define system process contracts
- [ ] Implement process handlers
- [ ] Wire into RhoRuntime
- [ ] Unit testing

### Week 2: Runtime Wiring
- [ ] Update Setup, RuntimeManager, ReportingCasper
- [ ] Integration testing
- [ ] System process availability verification

### Week 2-3: Demo Development
- [ ] Create basic job deployment examples
- [ ] Develop RNode-in-DMS demo
- [ ] Test and refine demo scripts
- [ ] Documentation

### Week 3: Polish & Stretch Goals
- [ ] Complete documentation and examples
- [ ] Implement shard deployment demo
- [ ] Performance optimization
- [ ] Security hardening

## Success Criteria

1. ✅ **NuNet DMS Connectivity**: F1R3FLY can communicate with local DMS via REST API
2. ✅ **Job Deployment**: Rholang contracts can deploy arbitrary compute jobs to DMS
3. ✅ **RNode Deployment**: Successfully launch F1R3FLY RNode inside DMS
4. ✅ **Demo Functionality**: Complete demo showing Rholang contract launching RNode
5. ⭐ **Stretch Goal**: Demo launching entire F1R3FLY shard on DMS

## Dependencies

- **NuNet DMS**: Binary installation or source build
- **Akka HTTP**: HTTP client library (already in project)
- **Spray JSON**: JSON serialization (already in project)
- **Docker**: Container runtime for job execution

## Security Considerations

- **Credential Management**: Secure handling of DMS actor DIDs
- **Network Security**: TLS/SSL for DMS communication
- **Resource Limits**: Proper job resource constraints
- **Access Control**: Capability-based permissions alignment

## Future Enhancements

- **Multi-DMS Support**: Connect to multiple DMS instances
- **Resource Monitoring**: Real-time compute resource tracking
- **Job Scheduling**: Advanced job placement and scheduling
- **Cost Optimization**: Economic optimization for compute allocation
- **Federation**: Cross-platform compute federation with other networks

## References

- [NuNet Documentation](https://docs.nunet.io/)
- [NuNet DMS Repository](https://gitlab.com/nunet/device-management-service)
- [F1R3FLY Ollama Integration](./OLLAMA.md)
- [F1R3FLY Architecture](./CLAUDE_SUMMARY.md)