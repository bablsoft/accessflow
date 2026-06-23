import { describe, expect, it } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { App, Form, Button } from 'antd';
import type { ReactNode } from 'react';
import '@/i18n';
import { GuardrailsFormSection } from './GuardrailsFormSection';

function Host({
  patterns = [],
  onValid,
}: {
  patterns?: string[];
  onValid?: (ok: boolean) => void;
}) {
  const [form] = Form.useForm();
  return (
    <Form
      form={form}
      initialValues={{ guardrail_patterns: patterns }}
      onFinish={() => onValid?.(true)}
      onFinishFailed={() => onValid?.(false)}
    >
      <GuardrailsFormSection />
      <Button htmlType="submit">submit</Button>
    </Form>
  );
}

function wrap(node: ReactNode) {
  return (
    <App>{node}</App>
  );
}

describe('GuardrailsFormSection', () => {
  it('renders the section header and add button', () => {
    render(wrap(<Host />));

    expect(screen.getByText('Guardrails')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /add pattern/i })).toBeInTheDocument();
  });

  it('adds and removes a pattern row', () => {
    render(wrap(<Host />));

    fireEvent.click(screen.getByRole('button', { name: /add pattern/i }));
    expect(screen.getByTestId('guardrail-pattern')).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: /remove pattern/i }));
    expect(screen.queryByTestId('guardrail-pattern')).not.toBeInTheDocument();
  });

  it('rejects an invalid regex on submit', async () => {
    const results: boolean[] = [];
    render(wrap(<Host patterns={['(']} onValid={(ok) => results.push(ok)} />));

    fireEvent.click(screen.getByRole('button', { name: 'submit' }));

    expect(await screen.findByText('Not a valid regular expression')).toBeInTheDocument();
    await waitFor(() => expect(results).toContain(false));
  });

  it('accepts a valid regex on submit', async () => {
    const results: boolean[] = [];
    render(wrap(<Host patterns={['drop\\s+table']} onValid={(ok) => results.push(ok)} />));

    fireEvent.click(screen.getByRole('button', { name: 'submit' }));

    await waitFor(() => expect(results).toContain(true));
    expect(screen.queryByText('Not a valid regular expression')).not.toBeInTheDocument();
  });
});
