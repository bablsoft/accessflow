// Runtime configuration override for the setup-variant e2e stack.
//
// Bind-mounted by docker-compose.e2e.setup.yml over the frontend image's baked-in
// /usr/share/nginx/html/runtime-config.js. Points the browser at the variant
// backend on host port 8081, which the variant stack publishes instead of the
// main stack's 8080. Same resolution precedence as the production file:
//   1. window.__APP_CONFIG__ (this file)
//   2. import.meta.env.VITE_* (build-time, npm run dev only)
//   3. localhost defaults
window.__APP_CONFIG__ = {
  apiBaseUrl: "http://localhost:8081",
  wsUrl: "ws://localhost:8081/ws",
};
