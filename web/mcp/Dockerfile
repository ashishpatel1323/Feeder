# feeder-mcp over HTTP, for a cloud host with a persistent volume at /data.
# Secrets (set on the host, never baked in): FEEDER_SYNC_CODE, FEEDER_SYNC_KEY, FEEDER_MCP_TOKEN.
FROM node:22-bookworm-slim AS build
WORKDIR /app
RUN apt-get update && apt-get install -y --no-install-recommends python3 make g++ && rm -rf /var/lib/apt/lists/*
COPY package.json package-lock.json ./
RUN npm install --no-audit --no-fund
COPY tsconfig.json ./
COPY src ./src
RUN npx tsc -p . && npm prune --omit=dev

FROM node:22-bookworm-slim
WORKDIR /app
# yt-dlp for YouTube captions (standalone binary, needs python3)
RUN apt-get update && apt-get install -y --no-install-recommends python3 ca-certificates curl \
  && curl -fsSL -o /usr/local/bin/yt-dlp https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp \
  && chmod +x /usr/local/bin/yt-dlp && apt-get purge -y curl && rm -rf /var/lib/apt/lists/*
COPY --from=build /app/node_modules ./node_modules
COPY --from=build /app/dist ./dist
COPY package.json ./
ENV NODE_ENV=production FEEDER_MCP_DIR=/data FEEDER_ZIM_DIRS=/data/zim HOST=0.0.0.0 PORT=8080 YTDLP=/usr/local/bin/yt-dlp
VOLUME /data
EXPOSE 8080
CMD ["node", "dist/http.js"]
