import type { ReactNode } from 'react';
import type { PrincipalType } from '@/types/api';
import { PrincipalTypeTag } from './PrincipalTypeTag';

/**
 * `optionRender` for a user `<Select>` fed by `userSelectOptions` (#875): the label plus the
 * service-account badge. Typed loosely so it fits AntD's `DefaultOptionType` on any `Select`.
 */
export function renderUserOption(option: {
  data: { label?: ReactNode; principal_type?: PrincipalType };
}) {
  return (
    <span style={{ display: 'inline-flex', alignItems: 'center', gap: 8 }}>
      <span>{option.data.label}</span>
      <PrincipalTypeTag principalType={option.data.principal_type} />
    </span>
  );
}
