# syntax=docker/dockerfile:1.7

# Use the glibc-based slim image (not -alpine) so the install graph matches the
# CI environment (which runs on Ubuntu/glibc). `npm install` (rather than
# `npm ci`) so the build can auto-resolve cross-platform optional deps that
# the host-generated package-lock.json may not have pinned (e.g. WASM napi
# fallbacks the lockfile leaves implicit). The container's lockfile mutation
# doesn't escape — the runtime stage below only copies the built dist/.
FROM node:24-slim AS build
WORKDIR /app
ARG APP_VERSION=0.0.0
ENV VITE_APP_VERSION=$APP_VERSION
COPY package.json package-lock.json ./
RUN npm install --no-audit --no-fund
COPY . .
RUN npm run build

FROM nginx:alpine
COPY --from=build /app/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
