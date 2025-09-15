#!/bin/bash

echo "🚀 Starting NuNet DMS in standalone mode..."

# Set the passphrase environment variable
export DMS_PASSPHRASE="testpass123"

echo "✅ Passphrase set via DMS_PASSPHRASE environment variable"
echo "✅ Bootstrap peers disabled for standalone mode"
echo "📍 DMS will run on http://localhost:9999"
echo ""

# Start the DMS
./nunet run --context test-device