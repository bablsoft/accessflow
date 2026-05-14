# syntax=docker/dockerfile:1.7

FROM node:24-alpine AS build
WORKDIR /app
ARG APP_VERSION=0.0.0
ENV VITE_APP_VERSION=$APP_VERSION
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
RUN npm run build

FROM nginx:alpine
COPY --from=build /app/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
