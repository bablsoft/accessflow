export function userDisplay(
  displayName?: string | null,
  email?: string | null,
): string {
  const name = displayName?.trim();
  if (name) return name;
  const fallback = email?.trim();
  return fallback ?? '';
}
