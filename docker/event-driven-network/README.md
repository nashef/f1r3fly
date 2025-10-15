# Event-Driven F1R3FLY Devnet

Self-sustaining blockchain network that requires only ONE initial deploy to start, then runs indefinitely via dummy deploys and block-triggered autopropose.

## Quick Start

```bash
# 1. Start network
cd docker/event-driven-network
docker-compose up -d

# 2. Wait for genesis (2-3 minutes)
# All nodes need to output 'Making a transition to Running state.'
docker-compose logs -f | grep "Making a transition to Running state"

# 3. Make deploy with rust-client or any client
cd /path/to/rust-client
cargo run -- transfer --to-address "111129p33f7vaRrpLqK8Nr35Y2aacAjrR5pd6PCzqcdrMuPHzymczH" --amount 1

# 4. Done! Chain is now self-sustaining
# Monitor with:
docker-compose logs -f validator1 validator2 validator3
```

## How It Works

### Self-Sustaining Mechanism

After ONE initial deploy, the network becomes self-sustaining via a feedback loop:

1. **Initial Deploy** → Deploy-triggered autopropose → Validator creates first block
2. **Block Gossip** → Other validators receive the block
3. **Block-Triggered Autopropose** → Validators propose their own blocks
4. **Dummy Deploys** → Every block includes a dummy `Nil` deploy (harmless)
5. **Infinite Loop** → Steps 2-4 repeat forever with natural validator rotation

**Key Insight:** Dummy deploys ensure `deploys.nonEmpty` is ALWAYS true, so blocks never stop being created.

### Architecture Components

**1. Event-Driven Autopropose (`--autopropose` flag)**
- **Deploy-Triggered:** New deploy received → immediate block proposal
- **Block-Triggered:** New block validated → propose next block
- **Requires:** Both `--autopropose` flag AND `dev.deployer-private-key` in config

**2. Devnet Mode (Shared Dummy Deploy Key)**

**Key:** `61e594124ca6af84a5468d98b34a4f3431ef39c54c6cf07fe6fbf8b079ef64f6` (public)

**Purpose:** All validators use this shared key to generate dummy `Nil` deploys in every block.

**Security Model:**
- ✅ Block signatures: Unique validator keys (secure)
- ✅ Bonding: Prevents unauthorized validators  
- ✅ Consensus: Casper CBC enforced
- ℹ️ Dummy deploys: Shared key (harmless - zero state impact)

**Why Safe:** Dummy deploys are just `Nil` (no-op), have zero gas cost, don't modify state, and have no economic value. Real security comes from unique validator keys signing blocks and consensus validation.

## Configuration

### Environment Variables (.env)

```bash
# Bootstrap node
BOOTSTRAP_NODE_ID=1e780e5dfbe0a3d9470a2b414f502d59402e09c2
BOOTSTRAP_HOST=rnode.bootstrap
BOOTSTRAP_PRIVATE_KEY=3596fed0e3a1ecd9a5b8097c49c8d65e9c0bb3bfc7461f3e5a8ecab42e8d4f03
BOOTSTRAP_PUBLIC_KEY=047d0e60f6f5d56ce6368ceb260e10c6e18b01056e95bc62f2e29b9c89e5c01c59d0f96a4a829e55f4d0e78ba00c41754a1b1da51b29b8c1bfbba68ddf5c00d8d9

# Validator 1
VALIDATOR1_HOST=rnode.validator1
VALIDATOR1_PRIVATE_KEY=357cdc4201a5650830e0bc5a03299a30038d9934ba4c7ab73ec164ad82471ff9
VALIDATOR1_PUBLIC_KEY=04fa70d7be5eb750e0915c0f6d19e7085d18bb1c22d030feb2a877ca2cd226d04438aa819359c56c720142fbc66e9da03a5ab960a3d8b75363a226b7c800f60420

# Validator 2
VALIDATOR2_HOST=rnode.validator2
VALIDATOR2_PRIVATE_KEY=fe8e28a1fbb6b091c7b63dcbbbb32c76bb53cffa0bcf41b0ae4c1e5e7b09f7ca
VALIDATOR2_PUBLIC_KEY=04837a4cff833e3157e3135d7b40b8e1f33c6e6b5a4342b9fc784230ca4c4f9d356f258debef56ad4984726d6ab3e7709e1632ef079b4bcd653db00b68b2df065f

# Validator 3
VALIDATOR3_HOST=rnode.validator3
VALIDATOR3_PRIVATE_KEY=8d62d68a4ec766f4a95e21d54d5bc5ccfcafcb09af062fcc0e43aba701b4e808
VALIDATOR3_PUBLIC_KEY=0457febafcc25dd34ca5e5c025cd445f60e5ea6918931a54eb8c3a204f51760248090b0c757c2bdad7b8c4dca757e109f8ef64737d90712724c8216c94b4ae661c

# Read-only observer
READONLY_HOST=rnode.readonly

# OpenAI (optional)
OPENAI_ENABLED=false
OPENAI_SCALA_CLIENT_API_KEY=
```

### Validator Config (conf/shared-rnode.conf)

Critical settings for self-sustaining operation:

```hocon
dev-mode = true

dev {
  # Shared dummy deploy key (public, used by all validators)
  # This key generates harmless Nil deploys to keep chain progressing
  deployer-private-key = "61e594124ca6af84a5468d98b34a4f3431ef39c54c6cf07fe6fbf8b079ef64f6"
}
```

## Network Details

### Ports

| Port  | Purpose              |
|-------|----------------------|
| 40400 | F1R3FLY Protocol     |
| 40401 | gRPC External API    |
| 40402 | gRPC Internal API    |
| 40403 | HTTP API             |
| 40404 | Kademlia Discovery   |
| 40405 | Admin HTTP API       |

### Services

- **bootstrap:** Ceremony coordinator (not a validator)
- **validator1/2/3:** Bonded validators proposing blocks
- **readonly:** Observer node for queries

## Monitoring

```bash
# Check container status
docker-compose ps

# View logs
docker-compose logs -f validator1          # Single validator
docker-compose logs -f validator1 validator2 validator3  # All validators
docker-compose logs -f                     # All containers

# API queries
curl http://localhost:40403/api/status
curl http://localhost:40403/api/blocks?depth=20

# Check block production (using rust-client)
cd /path/to/rust-client
cargo run -- show-main-chain --host localhost --port 40412 --depth 10
```
