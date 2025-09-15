#!/bin/bash

# NuNet DMS Local Setup Script
# This script sets up a standalone NuNet Device Management Service for F1R3FLY integration testing

set -e  # Exit on any error

# Configuration
NUNET_DIR="./nunet-local"
NUNET_VERSION="latest"  # or specify a version like "v0.4.0"
PLATFORM=$(uname -s | tr '[:upper:]' '[:lower:]')
ARCH=$(uname -m)

# Map architecture names
case $ARCH in
    x86_64) ARCH="amd64" ;;
    arm64|aarch64) ARCH="arm64" ;;
    *) echo "Unsupported architecture: $ARCH"; exit 1 ;;
esac

echo "🚀 Setting up NuNet DMS for F1R3FLY integration..."
echo "Platform: $PLATFORM-$ARCH"
echo "Installation directory: $NUNET_DIR"

# Create and enter nunet directory
mkdir -p "$NUNET_DIR"
cd "$NUNET_DIR"

# Function to download NuNet binary
download_nunet() {
    echo "📥 Downloading NuNet DMS binary..."

    # Try different possible download URLs
    BINARY_NAME="nunet"
    DOWNLOAD_URL=""

    # Method 1: Try GitLab releases (most likely)
    if [ -z "$DOWNLOAD_URL" ]; then
        GITLAB_URL="https://gitlab.com/nunet/device-management-service/-/releases/latest/downloads/nunet-${PLATFORM}-${ARCH}"
        echo "Trying GitLab releases: $GITLAB_URL"
        if curl -fsSL --head "$GITLAB_URL" > /dev/null 2>&1; then
            DOWNLOAD_URL="$GITLAB_URL"
        fi
    fi

    # Method 2: Try alternative naming
    if [ -z "$DOWNLOAD_URL" ]; then
        ALT_URL="https://gitlab.com/nunet/device-management-service/-/releases/latest/downloads/dms-${PLATFORM}-${ARCH}"
        echo "Trying alternative naming: $ALT_URL"
        if curl -fsSL --head "$ALT_URL" > /dev/null 2>&1; then
            DOWNLOAD_URL="$ALT_URL"
        fi
    fi

    # Method 3: Build from source if no binary available
    if [ -z "$DOWNLOAD_URL" ]; then
        echo "⚠️  No pre-built binary found. Building from source..."
        build_from_source
        return
    fi

    echo "Downloading from: $DOWNLOAD_URL"
    curl -fsSL "$DOWNLOAD_URL" -o "$BINARY_NAME"
    chmod +x "$BINARY_NAME"

    echo "✅ Downloaded NuNet binary successfully"
}

# Function to build from source
build_from_source() {
    echo "🔨 Building NuNet DMS from source..."

    # Check for Go installation
    if ! command -v go &> /dev/null; then
        echo "❌ Go is required to build from source. Please install Go first."
        echo "Visit: https://golang.org/doc/install"
        exit 1
    fi

    # Clone and build
    echo "Cloning repository..."
    git clone https://gitlab.com/nunet/device-management-service.git build
    cd build

    echo "Building binary..."
    go build -o ../nunet ./cmd/

    cd ..
    rm -rf build

    echo "✅ Built NuNet DMS from source successfully"
}

# Function to initialize configuration
init_config() {
    echo "🔧 Initializing NuNet configuration..."

    # Create config directory
    mkdir -p config keys logs

    # Generate device key
    echo "Generating device keypair..."
    if [ -f ./nunet ]; then
        ./nunet key new device-local || echo "Key may already exist"
    else
        echo "⚠️  Binary not found, creating mock key setup"
        mkdir -p ~/.nunet/keys
        echo "mock-private-key" > ~/.nunet/keys/device-local.pem
    fi

    # Create basic configuration file
    cat > config/dms_config.json << EOF
{
  "api": {
    "listen_address": "127.0.0.1:9999",
    "cors_enabled": true
  },
  "actor": {
    "default_did": "did:nunet:device-local",
    "inbox_size": 1000
  },
  "network": {
    "libp2p": {
      "listen_addresses": ["/ip4/127.0.0.1/tcp/4001"],
      "bootstrap_peers": []
    }
  },
  "compute": {
    "executors": ["docker"],
    "default_resources": {
      "cpu": "2",
      "memory": "4Gi",
      "storage": "10Gi"
    }
  },
  "security": {
    "capability_timeout": "1h",
    "job_timeout": "24h"
  },
  "logging": {
    "level": "info",
    "file": "./logs/dms.log"
  }
}
EOF

    echo "✅ Configuration initialized"
}

# Function to start DMS
start_dms() {
    echo "🚀 Starting NuNet DMS..."

    # Set environment variables
    export NUNET_CONFIG_FILE="./config/dms_config.json"
    export NUNET_LOG_LEVEL="info"
    export NUNET_API_HOST="127.0.0.1"
    export NUNET_API_PORT="9999"

    echo "Configuration file: $NUNET_CONFIG_FILE"
    echo "API endpoint: http://127.0.0.1:9999"
    echo "Logs: ./logs/dms.log"
    echo ""

    # Check if Docker is available
    if command -v docker &> /dev/null; then
        echo "✅ Docker found - container execution will be available"
    else
        echo "⚠️  Docker not found - only native execution will be available"
    fi

    echo "Starting DMS (press Ctrl+C to stop)..."
    echo "----------------------------------------"

    # Start the DMS
    if [ -f ./nunet ]; then
        ./nunet run --context device-local
    else
        echo "❌ NuNet binary not found!"
        echo "Please check the download or build process."
        exit 1
    fi
}

# Function to create test scripts
create_test_scripts() {
    echo "📝 Creating test scripts..."

    # Create test script
    cat > test-dms.sh << 'EOF'
#!/bin/bash

echo "🧪 Testing NuNet DMS connectivity..."

# Test 1: Actor handle
echo "1. Testing actor handle endpoint..."
curl -s http://localhost:9999/actor/handle || echo "❌ Actor handle test failed"

# Test 2: Health check (if available)
echo "2. Testing health endpoint..."
curl -s http://localhost:9999/health || echo "ℹ️  Health endpoint not available"

# Test 3: Deploy test job
echo "3. Testing job deployment..."
cat > test-job.json << 'EOJ'
{
  "to": "did:nunet:device-local",
  "from": "did:nunet:test-client",
  "behavior": "deploy-job",
  "payload": {
    "image": "busybox:latest",
    "command": ["echo", "Hello from NuNet!"],
    "resources": {
      "cpu": "0.5",
      "memory": "512Mi"
    }
  }
}
EOJ

curl -s -X POST \
  -H "Content-Type: application/json" \
  -d @test-job.json \
  http://localhost:9999/actor/send || echo "❌ Job deployment test failed"

rm -f test-job.json
echo "✅ DMS connectivity tests completed"
EOF

    chmod +x test-dms.sh

    # Create F1R3FLY integration test
    cat > test-f1r3fly-integration.sh << 'EOF'
#!/bin/bash

echo "🔗 Testing F1R3FLY + NuNet integration..."

# Check if F1R3FLY is built
if [ ! -f ../node/target/universal/stage/bin/rnode ]; then
    echo "❌ F1R3FLY not built. Please run 'sbt stage' first."
    exit 1
fi

echo "Starting F1R3FLY with NuNet enabled..."
export NUNET_ENABLED=true
export NUNET_VALIDATE_CONNECTION=false

cd ..
./node/target/universal/stage/bin/rnode run --standalone --repl
EOF

    chmod +x test-f1r3fly-integration.sh

    echo "✅ Test scripts created:"
    echo "  - test-dms.sh: Test DMS connectivity"
    echo "  - test-f1r3fly-integration.sh: Test F1R3FLY integration"
}

# Function to show usage instructions
show_usage() {
    cat << EOF

🎉 NuNet DMS setup completed!

📁 All files are in: $NUNET_DIR/

🚀 To start the DMS:
   cd $NUNET_DIR
   ./nunet run --context device-local

🧪 To test the setup:
   ./test-dms.sh

🔗 To test F1R3FLY integration:
   ./test-f1r3fly-integration.sh

🌐 API will be available at: http://localhost:9999

📋 Key files created:
   - nunet                    (DMS binary)
   - config/dms_config.json   (Configuration)
   - logs/                    (Log files)
   - keys/                    (Cryptographic keys)

🗑️  To clean up completely:
   rm -rf $NUNET_DIR

📚 For more information:
   - NuNet docs: https://docs.nunet.io/
   - F1R3FLY integration: ../NUNET.md

EOF
}

# Main execution
main() {
    echo "Starting NuNet DMS setup..."

    # Download or build NuNet
    download_nunet

    # Initialize configuration
    init_config

    # Create test scripts
    create_test_scripts

    # Show usage instructions
    show_usage

    # Ask if user wants to start now
    echo -n "🚀 Start DMS now? (y/N): "
    read -r response
    if [[ "$response" =~ ^[Yy]$ ]]; then
        start_dms
    else
        echo "Setup complete. Run './nunet run --context device-local' to start the DMS."
    fi
}

# Handle cleanup on exit
cleanup() {
    echo ""
    echo "🛑 Stopping DMS..."
    exit 0
}

trap cleanup SIGINT SIGTERM

# Run main function
main "$@"
EOF