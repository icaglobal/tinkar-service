#!/bin/bash
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
GEN_DIR="$SCRIPT_DIR/src/tinkar_python/_generated"
SCHEMA_DIR="$SCRIPT_DIR/../tinkar-schema"
PROTO_DIR="$SCRIPT_DIR/../tinkar-core/service/src/main/proto"

mkdir -p "$GEN_DIR"
touch "$GEN_DIR/__init__.py"

uv run python -m grpc_tools.protoc \
    -I"$SCHEMA_DIR" \
    -I"$PROTO_DIR" \
    --python_out="$GEN_DIR" \
    --pyi_out="$GEN_DIR" \
    --grpc_python_out="$GEN_DIR" \
    "$PROTO_DIR/tinkar_search.proto"

uv run python -m grpc_tools.protoc \
    -I"$SCHEMA_DIR" \
    --python_out="$GEN_DIR" \
    --pyi_out="$GEN_DIR" \
    "$SCHEMA_DIR/Tinkar.proto"

# Fix relative imports
sed -i '' 's/^import Tinkar_pb2/from . import Tinkar_pb2/' "$GEN_DIR/tinkar_search_pb2.py"
sed -i '' 's/^import tinkar_search_pb2/from . import tinkar_search_pb2/' "$GEN_DIR/tinkar_search_pb2_grpc.py"


echo "Proto generation complete!"
