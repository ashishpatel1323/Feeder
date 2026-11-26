#!/usr/bin/env bash
# Runs on the VM (called by deploy.sh). Idempotent.
set -euo pipefail
: "${HOST_NAME:?}"
cd ~/feeder-mcp

if ! command -v docker >/dev/null; then
  sudo apt-get update -qq
  sudo DEBIAN_FRONTEND=noninteractive apt-get install -y -qq docker.io iptables-persistent >/dev/null
  sudo systemctl enable --now docker
fi

# Oracle's Ubuntu images reject everything but SSH in iptables, on top of the VCN security list
for p in 80 443; do
  sudo iptables -C INPUT -p tcp --dport $p -j ACCEPT 2>/dev/null || sudo iptables -I INPUT 5 -p tcp --dport $p -j ACCEPT
done
sudo netfilter-persistent save >/dev/null 2>&1 || true

sudo docker build -q -t feeder-mcp . >/dev/null
sudo docker network inspect feeder >/dev/null 2>&1 || sudo docker network create feeder >/dev/null
sudo docker volume inspect feeder-data >/dev/null 2>&1 || sudo docker volume create feeder-data >/dev/null

sudo docker rm -f feeder-mcp >/dev/null 2>&1 || true
sudo docker run -d --name feeder-mcp --restart unless-stopped --network feeder \
  -v feeder-data:/data feeder-mcp >/dev/null

mkdir -p ~/caddy
cat > ~/caddy/Caddyfile <<CADDY
${HOST_NAME} {
  encode gzip
  reverse_proxy feeder-mcp:8080
}
CADDY
sudo docker rm -f caddy >/dev/null 2>&1 || true
sudo docker run -d --name caddy --restart unless-stopped --network feeder -p 80:80 -p 443:443 \
  -v ~/caddy/Caddyfile:/etc/caddy/Caddyfile -v caddy-data:/data caddy:2 >/dev/null

# The HTTP server writes its token on first start
for i in $(seq 1 30); do sudo docker exec feeder-mcp test -f /data/http-token && break; sleep 1; done
echo "running: $(sudo docker ps --format '{{.Names}}' | tr '\n' ' ')"
