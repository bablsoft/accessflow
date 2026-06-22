import js from '@eslint/js';
import globals from 'globals';
import reactHooks from 'eslint-plugin-react-hooks';
import reactRefresh from 'eslint-plugin-react-refresh';
import tseslint from 'typescript-eslint';

export default tseslint.config(
  // src/sw.ts runs in the ServiceWorker global scope (worker globals, self.__WB_MANIFEST)
  // and is bundled by vite-plugin-pwa, not by the app build — exclude it from app linting.
  { ignores: ['dist', 'playwright-report', 'test-results', 'coverage', 'src/sw.ts'] },
  {
    extends: [js.configs.recommended, ...tseslint.configs.recommended],
    files: ['**/*.{ts,tsx}'],
    languageOptions: {
      ecmaVersion: 2022,
      globals: globals.browser,
    },
    plugins: {
      'react-hooks': reactHooks,
      'react-refresh': reactRefresh,
    },
    rules: {
      ...reactHooks.configs.recommended.rules,
      // The set-state-in-effect rule (new in v7) flags valid synchronization
      // effects (e.g. closing a mobile drawer on route change, debouncing
      // analysis on SQL change). Downgrade to warning until we have a more
      // precise lint or a useEvent-based refactor.
      'react-hooks/set-state-in-effect': 'warn',
      'react-refresh/only-export-components': [
        'warn',
        { allowConstantExport: true },
      ],
      '@typescript-eslint/no-unused-vars': ['warn', { argsIgnorePattern: '^_' }],
    },
  },
);
