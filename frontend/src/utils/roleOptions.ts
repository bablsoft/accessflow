import type { TFunction } from 'i18next';
import type { RoleSummary } from '@/types/api';
import { roleLabel } from './enumLabels';

export interface RoleOption {
  value: string;
  label: string;
}

/** System role names are localized via `enums.role.<NAME>`; custom names render verbatim. */
export function roleDisplayName(
  t: TFunction,
  role: Pick<RoleSummary, 'name' | 'system'>,
): string {
  return role.system ? roleLabel(t, role.name) : role.name;
}

/**
 * Options for a role `<Select>` fed from `listRoles()` (AF-522). `by` picks the wire value:
 * the role id (user assignment endpoints) or the role NAME (policy/plan scoping fields).
 */
export function roleSelectOptions(
  roles: readonly RoleSummary[],
  t: TFunction,
  by: 'id' | 'name',
): RoleOption[] {
  return roles.map((role) => ({
    value: by === 'id' ? role.id : role.name,
    label: roleDisplayName(t, role),
  }));
}
