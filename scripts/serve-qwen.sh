#!/usr/bin/env bash
# Serve Qwen3.6-35B-A3B-8bit locally via mlx-lm with an OpenAI-compatible API.
# Usage: scripts/serve-qwen.sh   (env overrides: QWEN_MODEL_REPO, QWEN_PORT)
set -euo pipefail

MODEL_REPO="${QWEN_MODEL_REPO:-mlx-community/Qwen3.6-35B-A3B-8bit}"
PORT="${QWEN_PORT:-8080}"
VENV="$(cd "$(dirname "$0")/.." && pwd)/.venv-mlx"

if ! command -v python3 >/dev/null; then
  echo "python3 is required (brew install python)" >&2
  exit 1
fi

if [ ! -d "$VENV" ]; then
  python3 -m venv "$VENV"
fi
# shellcheck disable=SC1091
source "$VENV/bin/activate"
pip install --quiet --upgrade mlx-lm "huggingface_hub[cli]"

echo "Downloading $MODEL_REPO (tens of GB on first run; cached afterwards)..."
huggingface-cli download "$MODEL_REPO" >/dev/null

echo "Serving $MODEL_REPO on http://127.0.0.1:$PORT/v1 ..."
exec mlx_lm.server --model "$MODEL_REPO" --port "$PORT"
