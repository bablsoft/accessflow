import type { PrincipalType, User } from '@/types/api';

/**
 * One option of a user `<Select>`. `label` stays a plain string so the pickers' `optionFilterProp`
 * / `filterOption` keep searching on it; the service-account badge is drawn by `optionRender`.
 */
export interface UserSelectOption {
  value: string;
  label: string;
  principal_type: PrincipalType;
}

/** The label a user picker shows: "Name (email)" when a display name exists, else the email. */
export function userOptionLabel(user: Pick<User, 'email' | 'display_name'>): string {
  return user.display_name ? `${user.display_name} (${user.email})` : user.email;
}

/** Options for a user `<Select>` fed from `listUsers()`; absent `principal_type` = a person (#875). */
export function userSelectOptions(
  users: readonly Pick<User, 'id' | 'email' | 'display_name' | 'principal_type'>[],
): UserSelectOption[] {
  return users.map((user) => ({
    value: user.id,
    label: userOptionLabel(user),
    principal_type: user.principal_type ?? 'HUMAN',
  }));
}

