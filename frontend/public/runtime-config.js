// Runtime configuration for AccessFlow frontend.
//
// Loaded synchronously from index.html before the React bundle, so app code can read
// window.__APP_CONFIG__ at module-init time. Replace this file at deploy time (mount via
// Kubernetes ConfigMap, Docker bind-mount, or `sed` in an entrypoint script) to point the
// same built image at a different backend without rebuilding.
//
// Resolution precedence in src/config/runtimeConfig.ts:
//   1. window.__APP_CONFIG__ (this file)
//   2. import.meta.env.VITE_* (build-time, npm run dev only)
//   3. localhost defaults
window.__APP_CONFIG__ = {
  apiBaseUrl: "http://localhost:8080",
  wsUrl: "ws://localhost:8080/ws",
};
