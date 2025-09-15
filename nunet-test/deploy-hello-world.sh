#!/bin/bash

echo "🚀 Deploying hello world container to NuNet DMS..."

# Set the passphrase environment variable
export DMS_PASSPHRASE="testpass123"
echo "✅ Using DMS_PASSPHRASE environment variable"
echo ""

# Deploy the hello world container
echo "📦 Creating deployment..."
./nunet actor cmd --context test-device --expiry "$(date -v+2H '+%Y-%m-%dT%H:%M:%SZ')" /dms/node/deployment/new --spec-file hello-world.yaml

echo ""
echo "📋 Checking deployment status..."
sleep 2

# List deployments
./nunet actor cmd --context test-device /dms/node/deployment/list

echo ""
echo "🐳 Checking Docker containers..."
docker ps