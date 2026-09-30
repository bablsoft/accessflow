import { Alert, Form } from 'antd';
import { useTranslation } from 'react-i18next';

interface Props {
  /** Index of the approver row inside the `approvers` Form.List. */
  name: number;
  /** Active service-account counts keyed by lower-cased role name. */
  countsByRole: ReadonlyMap<string, number>;
}

/**
 * Role-targeted approver rules expand to every holder of the role, service accounts included
 * (#1131) — say so when that is the case, so an agent never becomes an approver by accident.
 */
export function ApproverRoleServiceAccountWarning({ name, countsByRole }: Props) {
  const { t } = useTranslation();
  const role = Form.useWatch<string | null | undefined>(['approvers', name, 'role']);
  const count = role ? (countsByRole.get(role.trim().toLowerCase()) ?? 0) : 0;
  if (count < 1) return null;
  return (
    <Alert
      type="warning"
      showIcon
      data-testid="approver-role-service-account-warning"
      style={{ marginTop: 4 }}
      title={t('admin.review_plans.approver_role_service_account_warning', { count })}
    />
  );
}
