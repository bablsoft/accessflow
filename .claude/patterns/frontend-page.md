# Frontend page

**When to use:** A new route, page, or tab; or any new server data appearing in the UI.
**Canonical example:** `frontend/src/api/discovery.ts:19` (the `discoveryKeys` factory) consumed at `frontend/src/components/datasources/DiscoveryTab.tsx:68`
**Tests:** `frontend/src/pages/dashboard/DashboardPage.test.tsx`, `frontend/src/api/reviewPlans.test.ts`
**Related:** [frontend-form.md](frontend-form.md), [e2e-spec.md](e2e-spec.md), `docs/06-frontend.md`

## Shape

Every domain owns a key factory in `src/api/<domain>.ts`. Components never inline a key array:

```ts
// src/api/discovery.ts:19
export const discoveryKeys = {
  all: ['discovery'] as const,
  config: (datasourceId: string) => ['discovery', 'config', datasourceId] as const,
  findings: (datasourceId: string, filters: DiscoveryFindingFilters = {}) =>
    ['discovery', 'findings', datasourceId, filters] as const,
};
```

Reads go through TanStack Query; mutations invalidate the keys they affect — **including other
domains** when the server-side effect crosses one:

```tsx
// src/components/datasources/DiscoveryTab.tsx:68
const configQuery = useQuery({ queryKey: discoveryKeys.config(dsId), queryFn: ... });

const saveConfig = useMutation({
  mutationFn: (input: UpdateDiscoveryConfigInput) => updateDiscoveryConfig(dsId, input),
  onSuccess: (config) => {
    queryClient.setQueryData(discoveryKeys.config(dsId), config);
    message.success(t('datasources.settings.discovery.config_save_success'));
  },
  onError: (err) => {
    showApiError(message, err, (e) =>                       // never discards `err`
      apiErrorMessage(e, () => t('datasources.settings.discovery.config_save_error')),
    );
  },
});

// :137 — confirming a finding also mutates data-classification state
void queryClient.invalidateQueries({ queryKey: dataClassificationKeys.list(dsId) });
```

## Required (acceptance checklist)

- [ ] A `<domain>Keys` factory in `src/api/<domain>.ts`; hierarchical, `as const`, domain-prefixed.
- [ ] **TanStack Query for all server data.** No `useEffect` fetching. **No server data in
      Zustand** — the only legitimate stores are `authStore`, `notificationStore`,
      `preferencesStore`.
- [ ] Mutations invalidate the record key *and* the list key, plus any derived domain the server
      touched.
- [ ] Every user-visible string via `t()`. Enum labels via `src/utils/enumLabels.ts` helpers
      (`queryStatusLabel`, `dbTypeLabel`, …) and `enumOptions(VALUES, label, t)` for `<Select>`
      arrays — never `{ value: 'EMAIL', label: 'EMAIL' }`.
- [ ] Error toasts go through `showApiError(message, err, builder)`
      (`src/utils/showApiError.tsx:5`), where the builder is the domain handler from
      `src/utils/apiErrors.ts` or `apiErrorMessage(e, () => t('…'))`.
- [ ] `Skeleton` while loading (not a centred spinner); `<EmptyState>` when empty. Route groups
      lazy-loaded via `React.lazy`.
- [ ] Config only via `getApiBaseUrl()` / `getWsUrl()` from `src/config/runtimeConfig.ts:15`.
- [ ] All requests through `src/api/client.ts` — never a bare `fetch`.
- [ ] New user-facing flow → an `e2e/tests/` spec in the same PR ([e2e-spec.md](e2e-spec.md)).

## Anti-patterns

- **`onError: () => message.error(t('...'))`** → discards `err`, throwing away the server's
  `detail`, which is already localized by the backend's request-locale resolution. The static
  fallback should only appear when the envelope carries no detail. Passing a *static* builder to
  `showApiError` is the same bug wearing a helper.
- **Server data in Zustand** → two sources of truth, and no cache invalidation. If a non-React
  caller needs it, use `useQueryClient().getQueryData(...)`.
- **`useEffect` + `useState` to fetch** → no dedupe, no retry, no invalidation, and a race on
  unmount.
- **Inlining a query key** (`useQuery({ queryKey: ['discovery', 'config', id] })`) → the mutation
  that should invalidate it now has a second, subtly different literal, and the cache goes stale.
- **`import.meta.env` in a component** → bypasses the runtime-config precedence chain, so a
  container that overrides `runtime-config.js` at deploy time has no effect.
- **JWT in `localStorage`/`sessionStorage`** → XSS-exfiltratable. The access token lives in memory
  (Zustand); the refresh token is an `HttpOnly` cookie the frontend never reads.
- **Catching 401 in a component** → the Axios interceptor owns refresh-and-retry.
- **`as any` to silence a type error** → `strict: true` is load-bearing; fix the type.
- **A raw hex colour** → use the `--af-*` tokens; status/risk colours live in
  `src/utils/statusColors.ts` and `riskColors.ts`.

## Extending

WebSocket events map to invalidations, never to direct state writes — a WS payload is a hint that
something changed, not authoritative data. Re-fetch via REST after invalidating:
`query.status_changed` → `['queries', id]` + `['queries','list']`;
`review.new_request` → `['reviews','queue']`; `ai.analysis_complete` → `['queries', id]`.

Global `QueryClient` defaults live in `src/main.tsx` (`staleTime: 30_000`, `gcTime: 5*60_000`,
`refetchOnWindowFocus: false`, `retry: 1`). Overriding one per-call needs a comment saying why.

Adding a pure-logic module under `src/utils/` means adding it to the coverage `include` list in
`vite.config.ts` and shipping tests with it.
