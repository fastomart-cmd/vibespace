#!/usr/bin/env bash
# Run on the Oracle VM from /opt/vibespace/backend after pulling new code.
set -euo pipefail
npm ci
npm run build
npm run migrate          # applies new SQL files in migrations/ (idempotent, advisory-locked)
npm prune --omit=dev
sudo systemctl restart vibespace-api
sleep 3 && curl -fsS http://127.0.0.1:3000/health && echo " deployed"
