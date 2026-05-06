import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';
import path from 'node:path';

export default defineConfig({
  plugins: [react()],
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
        'src/mocks/analyzer.ts',
        'src/mocks/schema.ts',
        'src/mocks/delay.ts',
        'src/api/auth.ts',
        'src/api/client.ts',
        'src/store/authStore.ts',
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
