import type { UserSelectOption } from '@/utils/userOptions';
import { PrincipalTypeTag } from './PrincipalTypeTag';

/** `optionRender` for a user `<Select>` fed by `userSelectOptions`: the label plus the badge. */
export function renderUserOption(option: { data: UserSelectOption }) {
  return (
    <span style={{ display: 'inline-flex', alignItems: 'center', gap: 8 }}>
      <span>{option.data.label}</span>
      <PrincipalTypeTag principalType={option.data.principal_type} />
    </span>
  );
}
