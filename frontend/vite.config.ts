import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';
import path from 'node:path';
import { readFileSync } from 'node:fs';

const pkg = JSON.parse(
  readFileSync(path.resolve(__dirname, 'package.json'), 'utf8'),
) as { version: string };
const APP_VERSION = process.env.VITE_APP_VERSION ?? pkg.version;

export default defineConfig({
  plugins: [react()],
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
        'src/api/admin.ts',
        'src/api/auth.ts',
        'src/api/client.ts',
        'src/api/datasources.ts',
        'src/api/notifications.ts',
        'src/api/queries.ts',
        'src/api/reviewPlans.ts',
        'src/api/setup.ts',
        'src/components/datasources/ErDiagramTab.tsx',
        'src/components/datasources/erDiagramLayout.ts',
        'src/hooks/useSchemaIntrospect.ts',
        'src/hooks/useWebSocket.ts',
        'src/pages/admin/reviewPlanTemplateForm.ts',
        'src/pages/queries/buildTimelineStages.ts',
        'src/realtime/websocketManager.ts',
        'src/store/authStore.ts',
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
