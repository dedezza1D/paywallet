# Built from the repository root: the app depends on the shared workspace package in packages/core.
FROM node:24-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
COPY packages/core/package.json packages/core/
COPY frontend/package.json frontend/
RUN npm ci --no-audit --no-fund --workspace frontend
COPY packages/core packages/core
COPY frontend frontend
RUN npm run build --workspace frontend

FROM caddy:2.11-alpine
# Upgrades pick up Alpine security fixes released after the base image. The binary ships with the capability to bind
# ports below 1024, and a Kubernetes pod that drops every capability and forbids privilege escalation cannot even start
# it: Kubernetes listens on 8080, while Docker Compose runs it as root.
RUN apk upgrade --no-cache && setcap -r /usr/bin/caddy
COPY frontend/Caddyfile /etc/caddy/Caddyfile
COPY --from=build /app/frontend/dist /srv
