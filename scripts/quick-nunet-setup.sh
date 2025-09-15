#!/bin/bash

# Quick NuNet DMS Setup for F1R3FLY Testing
# This is a simplified setup script focused on getting DMS running quickly

set -e

NUNET_DIR="./nunet-test"
DMS_PORT="9999"

echo "🚀 Quick NuNet DMS Setup for F1R3FLY"
echo "====================================="

# Create isolated directory
mkdir -p "$NUNET_DIR"
cd "$NUNET_DIR"

echo "📁 Working in: $(pwd)"

# Function to download latest release
download_nunet() {
    echo "📥 Downloading NuNet DMS..."

    # Detect platform
    OS=$(uname -s | tr '[:upper:]' '[:lower:]')
    ARCH=$(uname -m)

    case $ARCH in
        x86_64) ARCH="amd64" ;;
        arm64|aarch64) ARCH="arm64" ;;
    esac

    # Use the working GitLab package download URL
    PACKAGE_URL="https://gitlab.com/nunet/device-management-service/-/package_files/228346036/download"

    echo "Downloading from: $PACKAGE_URL"

    if curl -fsSL "$PACKAGE_URL" -o nunet-package.zip; then
        echo "✅ Downloaded package successfully"

        # Extract the binary from the zip
        echo "📦 Extracting binary..."
        if command -v unzip &> /dev/null; then
            unzip -q nunet-package.zip
            # The zip contains dms_darwin_arm64 - rename to nunet
            if [ -f "dms_${OS}_${ARCH}" ]; then
                mv "dms_${OS}_${ARCH}" nunet
            elif [ -f "dms_darwin_arm64" ]; then
                # Fallback for the specific file we know exists
                mv "dms_darwin_arm64" nunet
            else
                echo "❌ Could not find expected binary in package"
                ls -la
                exit 1
            fi
            chmod +x nunet
            rm nunet-package.zip
            echo "✅ Binary extracted and setup successfully"
        else
            echo "❌ unzip command not found. Please install unzip and try again."
            exit 1
        fi
    else
        echo "❌ Download failed."
        echo "Please manually download the NuNet DMS package from:"
        echo "https://gitlab.com/nunet/device-management-service/-/packages"
        echo "Extract it and save the binary as 'nunet' in this directory."
        exit 1
    fi
}

# Setup basic configuration
setup_config() {
    echo "🔧 Setting up configuration..."

    # Create directories
    mkdir -p config logs keys

    # Basic DMS configuration
    cat > config/dms.json << EOF
{
  "api": {
    "listen_address": "127.0.0.1:${DMS_PORT}"
  },
  "logging": {
    "level": "info"
  }
}
EOF

    echo "✅ Configuration created"
}

# Create test utilities
create_tests() {
    echo "📝 Creating test utilities..."

    # Test connectivity
    cat > test-connection.sh << 'EOF'
#!/bin/bash
echo "Testing NuNet DMS connection..."
echo "Trying: http://localhost:9999/actor/handle"
curl -v http://localhost:9999/actor/handle 2>&1 | head -20
EOF
    chmod +x test-connection.sh

    # F1R3FLY integration test
    cat > test-f1r3fly.sh << 'EOF'
#!/bin/bash
echo "🔗 Testing F1R3FLY integration..."
echo "Make sure F1R3FLY is built with 'sbt stage'"
echo ""

cd ../..  # Go back to F1R3FLY root

export NUNET_ENABLED=true
export NUNET_VALIDATE_CONNECTION=false

echo "Starting F1R3FLY with NuNet enabled..."
echo "In the REPL, try:"
echo '  new nunetNodes(`rho:nunet:nodes`), result, stdout(`rho:io:stdout`) in {'
echo '    nunetNodes!(*result) |'
echo '    for(@nodes <- result) { stdout!(["Nodes:", nodes]) }'
echo '  }'
echo ""

./node/target/universal/stage/bin/rnode run --standalone --repl
EOF
    chmod +x test-f1r3fly.sh

    echo "✅ Test scripts created"
}

# Start DMS
start_dms() {
    echo "🚀 Starting NuNet DMS..."
    echo "Press Ctrl+C to stop"
    echo "API will be available at: http://localhost:${DMS_PORT}"
    echo "Logs will appear below:"
    echo "=========================="

    # Initialize if needed
    ./nunet key new test-device 2>/dev/null || echo "Key already exists"

    # Start DMS
    export NUNET_CONFIG=./config/dms.json
    ./nunet run --context test-device
}

# Main execution
main() {
    download_nunet
    setup_config
    create_tests

    echo ""
    echo "🎉 Setup complete!"
    echo ""
    echo "📋 Available commands:"
    echo "  ./nunet run --context test-device  # Start DMS"
    echo "  ./test-connection.sh               # Test DMS connection"
    echo "  ./test-f1r3fly.sh                  # Test F1R3FLY integration"
    echo ""
    echo "🗑️  To clean up: rm -rf $NUNET_DIR"
    echo ""

    read -p "🚀 Start DMS now? (y/N): " -n 1 -r
    echo
    if [[ $REPLY =~ ^[Yy]$ ]]; then
        start_dms
    fi
}

# Handle Ctrl+C gracefully
trap 'echo ""; echo "🛑 Stopped"; exit 0' INT

main "$@"