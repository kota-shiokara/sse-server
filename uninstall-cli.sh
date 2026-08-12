#!/usr/bin/env bash
# ~/.local/bin に置いた ssectl を消す。
set -euo pipefail

rm -f "$HOME/.local/bin/ssectl"

echo "removed: $HOME/.local/bin/ssectl"
