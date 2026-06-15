import { describe, expect, it, vi, beforeEach } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { App } from 'antd';
import type { ReactNode } from 'react';
import '@/i18n';

const { generateSqlMock } = vi.hoisted(() => ({ generateSqlMock: vi.fn() }));

vi.mock('@/api/queries', () => ({ generateSql: generateSqlMock }));

const { TextToSqlBar } = await import('./TextToSqlBar');

function wrap(node: ReactNode) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return (
    <QueryClientProvider client={client}>
      <App>{node}</App>
    </QueryClientProvider>
  );
}

describe('TextToSqlBar', () => {
  beforeEach(() => {
    generateSqlMock.mockReset();
  });

  it('disables generate until a prompt is entered, then emits the draft and its syntax', async () => {
    generateSqlMock.mockResolvedValue({
      sql: 'db.orders.find({})',
      ai_provider: 'ANTHROPIC',
      ai_model: 'm',
      prompt_tokens: 1,
      completion_tokens: 1,
      syntax: 'shell',
    });
    const onGenerated = vi.fn();
    render(wrap(<TextToSqlBar datasourceId="ds-1" onGenerated={onGenerated} />));

    const button = screen.getByRole('button', { name: /Generate query/i });
    expect(button).toBeDisabled();

    fireEvent.change(screen.getByLabelText('Describe your query'), {
      target: { value: 'orders last 5 days' },
    });
    expect(button).not.toBeDisabled();

    fireEvent.click(button);

    await waitFor(() => expect(onGenerated).toHaveBeenCalledWith('db.orders.find({})', 'shell'));
    expect(generateSqlMock).toHaveBeenCalledWith({
      datasource_id: 'ds-1',
      prompt: 'orders last 5 days',
    });
  });

  it('does not emit a draft when generation fails', async () => {
    generateSqlMock.mockRejectedValue(new Error('boom'));
    const onGenerated = vi.fn();
    render(wrap(<TextToSqlBar datasourceId="ds-1" onGenerated={onGenerated} />));

    fireEvent.change(screen.getByLabelText('Describe your query'), {
      target: { value: 'x' },
    });
    fireEvent.click(screen.getByRole('button', { name: /Generate query/i }));

    await waitFor(() => expect(generateSqlMock).toHaveBeenCalled());
    expect(onGenerated).not.toHaveBeenCalled();
  });
});
