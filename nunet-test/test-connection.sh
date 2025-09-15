#!/bin/bash
echo "Testing NuNet DMS connection..."
echo "Trying: http://localhost:9999/actor/handle"
curl -v http://localhost:9999/actor/handle 2>&1 | head -20
