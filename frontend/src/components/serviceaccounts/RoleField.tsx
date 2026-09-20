import { Alert, Form, Select } from 'antd';
import { useTranslation } from 'react-i18next';
import type { RoleSummary } from '@/types/api';
import { isReviewCapableRole, roleSelectOptions } from '@/utils/roleOptions';

interface RoleFieldProps {
  roles: readonly RoleSummary[];
  loading: boolean;
  /** The role currently selected in the form — drives the review-capable warning. */
  selectedRoleId: string | null | undefined;
  disabled?: boolean;
  extra?: string;
}

/**
 * The role select for a service account (#875), with the warning that matters here: a role that
 * carries any `*_REVIEW` permission makes the account an eligible approver.
 */
export function RoleField({ roles, loading, selectedRoleId, disabled, extra }: RoleFieldProps) {
  const { t } = useTranslation();
  const selected = roles.find((role) => role.id === selectedRoleId);
  return (
    <>
      <Form.Item
        name="role_id"
        label={t('admin.service_accounts.create_modal.label_role')}
        extra={extra}
        rules={[{ required: true, message: t('admin.service_accounts.validation.required') }]}
      >
        <Select
          options={roleSelectOptions(roles, t, 'id')}
          loading={loading}
          disabled={disabled}
          showSearch={{ optionFilterProp: 'label' }}
        />
      </Form.Item>
      {isReviewCapableRole(selected) && (
        <Alert
          type="warning"
          showIcon
          data-testid="review-role-warning"
          style={{ marginBottom: 16 }}
          title={t('admin.service_accounts.review_role_warning')}
        />
      )}
    </>
  );
}
