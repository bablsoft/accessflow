import { describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import type { ReactNode } from 'react';
import type { AiAnalysis } from '@/types/api';
import '@/i18n';

const { AiHintPanel } = await import('./AiHintPanel');

const analysis: AiAnalysis = {
  risk_level: 'LOW',
  risk_score: 10,
  summary: 'Looks fine.',
  issues: [],
  affects_rows: 1,
  prompt_tokens: 100,
  completion_tokens: 50,
  optimizations: [
    {
      type: 'INDEX',
      title: 'Add index on users(email)',
      rationale: 'Speeds up the lookup.',
      sql: 'CREATE INDEX idx_users_email ON users(email)',
    },
  ],
};

function wrap(node: ReactNode) {
  return <>{node}</>;
}

describe('AiHintPanel optimizations', () => {
  it('renders the optimizations section and forwards onApplySuggestion', () => {
    const onApply = vi.fn();
    render(
      wrap(
        <AiHintPanel
          analyzing={false}
          analysis={analysis}
          aiEnabled
          onApplySuggestion={onApply}
        />,
      ),
    );

    expect(screen.getByText('Add index on users(email)')).toBeInTheDocument();

    fireEvent.click(screen.getByText('Add index on users(email)'));
    fireEvent.click(screen.getByRole('button', { name: /Apply as draft/i }));

    expect(onApply).toHaveBeenCalledWith('CREATE INDEX idx_users_email ON users(email)');
  });

  it('renders no optimizations section when the analysis has none', () => {
    render(
      wrap(
        <AiHintPanel
          analyzing={false}
          analysis={{ ...analysis, optimizations: [] }}
          aiEnabled
        />,
      ),
    );

    expect(screen.queryByText('Add index on users(email)')).toBeNull();
  });
});
