import { describe, expect, it } from 'vitest';
import { act, render, screen, waitFor } from '@testing-library/react';
import { Form } from 'antd';
import type { FormInstance } from 'antd';
import '@/i18n';
import { ApproverRoleServiceAccountWarning } from './ApproverRoleServiceAccountWarning';

interface Values {
  approvers: { role: string | null }[];
}

const COUNTS = new Map([
  ['reviewer', 1],
  ['triage bots', 4],
]);

function renderWithRole(role: string | null) {
  let formRef: FormInstance<Values> | null = null;
  function Harness() {
    const [form] = Form.useForm<Values>();
    formRef = form;
    return (
      <Form form={form} initialValues={{ approvers: [{ role }] }}>
        <Form.Item name={['approvers', 0, 'role']} noStyle>
          <input aria-label="role" readOnly />
        </Form.Item>
        <ApproverRoleServiceAccountWarning name={0} countsByRole={COUNTS} />
      </Form>
    );
  }
  render(<Harness />);
  return () => formRef!;
}

describe('ApproverRoleServiceAccountWarning', () => {
  it('uses the singular form for one service account', () => {
    renderWithRole('REVIEWER');
    expect(screen.getByTestId('approver-role-service-account-warning')).toHaveTextContent(
      '1 service account holds this role and can approve.',
    );
  });

  it('matches custom role names case-insensitively and ignores surrounding space', () => {
    renderWithRole('  Triage Bots ');
    expect(screen.getByTestId('approver-role-service-account-warning')).toHaveTextContent(
      '4 service accounts hold this role and can approve.',
    );
  });

  it('renders nothing for a user-only rule without a role', () => {
    renderWithRole(null);
    expect(screen.queryByTestId('approver-role-service-account-warning')).toBeNull();
  });

  it('renders nothing for a role no service account holds', () => {
    renderWithRole('ANALYST');
    expect(screen.queryByTestId('approver-role-service-account-warning')).toBeNull();
  });

  it('follows the role as it changes', async () => {
    const form = renderWithRole('ANALYST');
    expect(screen.queryByTestId('approver-role-service-account-warning')).toBeNull();

    act(() => {
      form().setFieldValue(['approvers', 0, 'role'], 'reviewer');
    });
    expect(
      await screen.findByTestId('approver-role-service-account-warning'),
    ).toBeInTheDocument();

    act(() => {
      form().setFieldValue(['approvers', 0, 'role'], null);
    });
    await waitFor(() => {
      expect(screen.queryByTestId('approver-role-service-account-warning')).toBeNull();
    });
  });
});
