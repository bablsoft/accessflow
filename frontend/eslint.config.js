import js from '@eslint/js';
import globals from 'globals';
import reactHooks from 'eslint-plugin-react-hooks';
import reactRefresh from 'eslint-plugin-react-refresh';
import tseslint from 'typescript-eslint';

export default tseslint.config(
  // src/sw.ts runs in the ServiceWorker global scope (worker globals, self.__WB_MANIFEST)
  // and is bundled by vite-plugin-pwa, not by the app build — exclude it from app linting.
  {
    ignores: [
      'dist',
      'playwright-report',
      'test-results',
      'coverage',
      'src/sw.ts',
      // Ambient module augmentation only — no runtime code to lint, and the
      // type-aware project service does not treat it as a program root file.
      'src/i18n.d.ts',
      // Vendored Bklit chart components (shadcn registry) — third-party code, not linted.
      'src/components/charts/**',
      'src/components/shimmering-text.tsx',
    ],
  },
  {
    extends: [js.configs.recommended, ...tseslint.configs.recommended],
    files: ['**/*.{ts,tsx}'],
    languageOptions: {
      ecmaVersion: 2022,
      globals: globals.browser,
      // Type-aware parsing, enabled solely so @typescript-eslint/no-deprecated
      // can run. The full recommendedTypeChecked preset is deliberately NOT
      // adopted — it would also turn on no-floating-promises / no-misused-promises,
      // which is a separate cleanup.
      parserOptions: {
        projectService: true,
        tsconfigRootDir: import.meta.dirname,
      },
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
      // Catches upstream deprecations (antd, React, TanStack Query, …) at lint
      // time instead of by periodic manual sweep.
      '@typescript-eslint/no-deprecated': 'error',
    },
  },
  // Design-system guard: no hand-rolled page gutters, and no font sizes off the --text-* scale.
  // Prefer the utility classes in src/styles/utilities.css over an inline style.
  {
    files: ['src/pages/**/*.tsx', 'src/components/**/*.tsx'],
    ignores: ['**/*.test.tsx', '**/__tests__/**'],
    rules: {
      'no-restricted-syntax': [
        'error',
        {
          selector: "JSXAttribute[name.name='style'] Property[key.name='padding'][value.value=/28px/]",
          message: 'Use <PageBody> / <FilterBar> from @/components/layout instead of a hand-rolled page gutter.',
        },
        {
          selector:
            "JSXAttribute[name.name='style'] Property[key.name='fontSize'][value.raw=/^(9|9\\.5|10|10\\.5|11\\.5|12\\.5|13\\.5|15|17|18|19)$/]",
          message: 'Font size is off the type scale — use a --text-* token (11, 12, 13, 14, 16, 20, 24).',
        },
      ],
    },
  },
);
