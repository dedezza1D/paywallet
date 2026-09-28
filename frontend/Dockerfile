FROM node:24-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY . .
RUN npm run build

FROM caddy:2.11-alpine
# Upgrades pick up Alpine security fixes released after the base image. The binary ships with the capability to bind
# ports below 1024, and a Kubernetes pod that drops every capability and forbids privilege escalation cannot even start
# it: Kubernetes listens on 8080, while Docker Compose runs it as root.
RUN apk upgrade --no-cache && setcap -r /usr/bin/caddy
COPY Caddyfile /etc/caddy/Caddyfile
COPY --from=build /app/dist /srv
