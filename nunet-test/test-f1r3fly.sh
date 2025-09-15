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
