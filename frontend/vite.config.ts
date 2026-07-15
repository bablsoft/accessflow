import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';
import { VitePWA } from 'vite-plugin-pwa';
import path from 'node:path';
import { readFileSync } from 'node:fs';

const pkg = JSON.parse(
  readFileSync(path.resolve(__dirname, 'package.json'), 'utf8'),
) as { version: string };
const APP_VERSION = process.env.VITE_APP_VERSION ?? pkg.version;

export default defineConfig({
  plugins: [
    react(),
    // PWA (AF-444): we own the service worker source (src/sw.ts) for the push /
    // notificationclick handlers; Workbox injects the precache manifest for the
    // offline review-queue shell. Registration is manual (main.tsx) to honour the
    // strict CSP — no inline registration script.
    VitePWA({
      strategies: 'injectManifest',
      srcDir: 'src',
      filename: 'sw.ts',
      registerType: 'autoUpdate',
      injectRegister: false,
      manifest: {
        name: 'AccessFlow',
        short_name: 'AccessFlow',
        description: 'Database access governance — review and approve queries on the go.',
        theme_color: '#0a0a0a',
        background_color: '#0a0a0a',
        display: 'standalone',
        start_url: '/reviews',
        scope: '/',
        icons: [
          { src: '/pwa-icon.svg', sizes: 'any', type: 'image/svg+xml', purpose: 'any maskable' },
        ],
      },
      // The single-bundle SPA chunk (AntD + CodeMirror) exceeds Workbox's 2 MiB default; raise the
      // precache ceiling so the offline app shell includes it.
      injectManifest: { maximumFileSizeToCacheInBytes: 6 * 1024 * 1024 },
      devOptions: { enabled: false },
    }),
  ],
  define: {
    __APP_VERSION__: JSON.stringify(APP_VERSION),
  },
  resolve: {
    alias: {
      '@': path.resolve(__dirname, 'src'),
    },
  },
  server: { port: 5173 },
  test: {
    globals: true,
    environment: 'jsdom',
    setupFiles: ['./vitest.setup.ts'],
    css: true,
    // jsdom 29 rewrote CSSOM and is slower on initial getComputedStyle() calls
    // before the cache warms. AntD modal portals (e.g. ReviewQueuePage reject
    // modal) push past Vitest's 5s default on GitHub Actions runners. 15s
    // gives ~3x cushion without masking truly hung tests.
    testTimeout: 15_000,
    hookTimeout: 15_000,
    reporters: [
      'default',
      ['junit', { suiteName: 'AccessFlow Frontend' }],
    ],
    outputFile: { junit: './test-results/junit.xml' },
    coverage: {
      provider: 'v8',
      reporter: ['text', 'lcov', 'cobertura', 'json-summary', 'json'],
      reportsDirectory: './coverage',
      // Cover the pure-logic surface today. UI components/pages that depend
      // on CodeMirror, AntD, or routing are excluded until FE-09 lands tests
      // for them.
      include: [
        'src/utils/**/*.ts',
        'src/theme/**/*.ts',
        'src/config/runtimeConfig.ts',
        'src/config/version.ts',
        'src/mocks/delay.ts',
        'src/api/accessRequests.ts',
        'src/api/admin.ts',
        'src/api/anomalies.ts',
        'src/api/apiConnectors.ts',
        'src/api/auth.ts',
        'src/api/breakGlass.ts',
        'src/api/client.ts',
        'src/api/comments.ts',
        'src/api/compliance.ts',
        'src/api/connectors.ts',
        'src/api/dashboard.ts',
        'src/api/dataClassifications.ts',
        'src/api/datasourceHealth.ts',
        'src/api/datasources.ts',
        'src/api/maskingPolicies.ts',
        'src/api/notifications.ts',
        'src/api/organizations.ts',
        'src/api/push.ts',
        'src/api/queries.ts',
        'src/api/requestGroups.ts',
        'src/api/reviewPlans.ts',
        'src/api/roles.ts',
        'src/api/routingPolicies.ts',
        'src/api/rowSecurityPolicies.ts',
        'src/api/setup.ts',
        'src/api/slack.ts',
        'src/api/stepup.ts',
        'src/components/apigov/useApiAuthoring.ts',
        'src/components/datasources/ErDiagramTab.tsx',
        'src/components/datasources/erDiagramLayout.ts',
        'src/components/editor/useQueryAuthoring.ts',
        'src/hooks/useSchemaIntrospect.ts',
        'src/hooks/useTableSample.ts',
        'src/hooks/useWebSocket.ts',
        'src/hooks/usePushSubscription.ts',
        'src/utils/push.ts',
        'src/pages/admin/reviewPlanTemplateForm.ts',
        'src/pages/admin/routingPolicyForm.ts',
        'src/pages/queries/buildTimelineStages.ts',
        'src/pages/requestGroups/groupBuilder.ts',
        'src/pages/lifecycle/erasureConfigForm.ts',
        'src/realtime/collabProvider.ts',
        'src/realtime/websocketManager.ts',
        'src/store/authStore.ts',
        'src/store/preferencesStore.ts',
        'src/store/setupStore.ts',
      ],
      exclude: [
        '**/__tests__/**',
        '**/*.test.{ts,tsx}',
        '**/*.d.ts',
      ],
      thresholds: {
        lines: 90,
        functions: 90,
        statements: 90,
        branches: 80,
      },
    },
  },
});
