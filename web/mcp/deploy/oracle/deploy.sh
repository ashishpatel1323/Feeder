#!/usr/bin/env bash
# Deploys feeder-mcp to an Oracle Cloud (Always Free) Ubuntu VM, with HTTPS from Caddy on
# <ip>.sslip.io (no domain needed). Run from your Mac, in the feeder-mcp folder:
#
#   deploy/oracle/deploy.sh <public-ip> [path-to-ssh-private-key]
#
# Re-run it any time to ship a new version; the index and sync key stay on the VM's volume.
set -euo pipefail
IP="${1:?usage: deploy.sh <public-ip> [ssh-key]}"
KEY="${2:-}"
USER_AT="${OCI_USER:-ubuntu}@${IP}"
SSH=(ssh -o StrictHostKeyChecking=accept-new ${KEY:+-i "$KEY"})
HERE="$(cd "$(dirname "$0")/../.." && pwd)"
HOST="$(echo "$IP" | tr . -).sslip.io"

echo "→ Copying source to $USER_AT"
rsync -az --delete --exclude node_modules --exclude dist --exclude .git --exclude '*.db' \
  -e "${SSH[*]}" "$HERE/" "$USER_AT:feeder-mcp/"

echo "→ Installing and starting on the VM (first run takes a few minutes)"
"${SSH[@]}" "$USER_AT" "HOST_NAME=$HOST bash feeder-mcp/deploy/oracle/remote.sh"

TOKEN="$("${SSH[@]}" "$USER_AT" 'sudo docker exec feeder-mcp cat /data/http-token' | tr -d '\r\n')"
echo
echo "Done. MCP URL (keep it private, the token is in it):"
echo "  https://$HOST/mcp/$TOKEN"
echo
echo "One-time setup on the VM (paste Feeder's add-device link when asked):"
echo "  ${SSH[*]} -t $USER_AT 'sudo docker exec -it feeder-mcp node dist/cli.js setup && sudo docker exec feeder-mcp node dist/cli.js index'"
