import { describe, expect, it } from 'vitest';
import { render, screen, fireEvent, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { App, Form } from 'antd';
import type { ReactNode } from 'react';
import '@/i18n';
import { OrchestrationFormSection } from './OrchestrationFormSection';
import type { AiConfigModel } from '@/types/api';

function Host({
  enabled,
  models = [],
}: {
  enabled: boolean;
  models?: AiConfigModel[];
}) {
  const [form] = Form.useForm();
  return (
    <Form
      form={form}
      initialValues={{
        orchestration_enabled: enabled,
        voting_strategy: 'WEIGHTED_AVERAGE',
        voting_weight: 1,
        models,
      }}
    >
      <OrchestrationFormSection form={form} />
    </Form>
  );
}

function wrap(node: ReactNode) {
  return (
    <MemoryRouter>
      <App>{node}</App>
    </MemoryRouter>
  );
}

describe('OrchestrationFormSection', () => {
  it('hides voting/member fields when orchestration is disabled', () => {
    render(wrap(<Host enabled={false} />));

    expect(screen.getByText('Multi-model orchestration')).toBeInTheDocument();
    expect(screen.queryByText('Voting strategy')).not.toBeInTheDocument();
    expect(screen.queryByText('Add model')).not.toBeInTheDocument();
  });

  it('shows voting fields and member list when enabled', () => {
    render(wrap(<Host enabled />));

    expect(screen.getByText('Voting strategy')).toBeInTheDocument();
    expect(screen.getByText('Primary model weight')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /add model/i })).toBeInTheDocument();
  });

  it('renders an existing member row', () => {
    render(
      wrap(
        <Host
          enabled
          models={[{ provider: 'OLLAMA', model: 'llama3', weight: 2, enabled: true }]}
        />,
      ),
    );

    expect(screen.getByTestId('orchestration-member')).toBeInTheDocument();
    expect(screen.getByDisplayValue('llama3')).toBeInTheDocument();
  });

  it('adds and removes member rows', () => {
    render(wrap(<Host enabled />));

    expect(screen.queryByTestId('orchestration-member')).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: /add model/i }));
    expect(screen.getByTestId('orchestration-member')).toBeInTheDocument();

    const row = screen.getByTestId('orchestration-member');
    fireEvent.click(within(row).getByRole('button', { name: /remove model/i }));
    expect(screen.queryByTestId('orchestration-member')).not.toBeInTheDocument();
  });
});
