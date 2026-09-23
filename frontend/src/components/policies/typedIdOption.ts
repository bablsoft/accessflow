const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

export interface IdOption {
  value: string;
  label: string;
}

/**
 * Appends the typed search text as an option when it is a whole UUID the list does not contain.
 *
 * The simulation pickers read lists that are scoped to the caller: `GET /datasources` returns only
 * the caller's own datasources unless they hold QUERY_ADMIN or DATASOURCE_MANAGE, and
 * `GET /admin/users` needs USER_MANAGE. An auditor on the reverse index — or a custom role with
 * the simulation permission alone — can still paste an id rather than be locked out.
 */
export function withTypedId<O extends IdOption>(
  options: readonly O[],
  search: string,
  label: (id: string) => string,
): (O | IdOption)[] {
  const id = search.trim();
  if (!UUID_RE.test(id) || options.some((o) => o.value.toLowerCase() === id.toLowerCase())) {
    return [...options];
  }
  return [...options, { value: id, label: label(id) }];
}
