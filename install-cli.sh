#!/usr/bin/env bash
# ssectl をビルドして ~/.local/bin に置く。
set -euo pipefail

cd "$(dirname "$0")"

./gradlew :cli:installCli

mkdir -p "$HOME/.local/bin"
cp cli/build/install/ssectl "$HOME/.local/bin/ssectl"

echo "installed: $HOME/.local/bin/ssectl"
