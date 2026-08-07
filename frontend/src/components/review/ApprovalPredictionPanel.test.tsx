import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import '@/i18n';
import type { ApprovalPredictionDetail } from '@/types/api';
import { ApprovalPredictionPanel } from './ApprovalPredictionPanel';

function prediction(overrides: Partial<ApprovalPredictionDetail> = {}): ApprovalPredictionDetail {
  return {
    id: 'ap-1',
    probability: 0.78,
    skipped: false,
    skipped_reason: null,
    failed: false,
    created_at: '2026-08-01T10:00:00Z',
    ...overrides,
  };
}

describe('ApprovalPredictionPanel', () => {
  it('shows a pending message while the query is still awaiting a decision', () => {
    render(<ApprovalPredictionPanel prediction={null} status="PENDING_REVIEW" />);
    expect(screen.getByText(/computing the approval likelihood/i)).toBeInTheDocument();
  });

  it('shows an unavailable message once the query is terminal and no row landed', () => {
    render(<ApprovalPredictionPanel status="EXECUTED" />);
    expect(screen.getByText(/no approval likelihood is available/i)).toBeInTheDocument();
  });

  it('renders the percentage and the advisory note on the happy path', () => {
    render(<ApprovalPredictionPanel prediction={prediction()} status="PENDING_REVIEW" />);
    expect(screen.getByText(/historical approval likelihood/i)).toBeInTheDocument();
    expect(screen.getByTestId('approval-prediction-badge')).toHaveTextContent('78%');
    expect(screen.getByText(/advisory only/i)).toBeInTheDocument();
  });

  it('renders the failure sentinel', () => {
    render(
      <ApprovalPredictionPanel
        prediction={prediction({ probability: null, failed: true })}
        status="PENDING_REVIEW"
      />,
    );
    expect(screen.getByRole('status')).toHaveTextContent(/could not be computed/i);
  });

  it('localizes the DISABLED skip reason', () => {
    render(
      <ApprovalPredictionPanel
        prediction={prediction({ probability: null, skipped: true, skipped_reason: 'DISABLED' })}
        status="PENDING_REVIEW"
      />,
    );
    expect(screen.getByRole('status')).toHaveTextContent(/switched off for this organization/i);
  });

  it('localizes the MODEL_NOT_SERVING skip reason', () => {
    render(
      <ApprovalPredictionPanel
        prediction={prediction({
          probability: null,
          skipped: true,
          skipped_reason: 'MODEL_NOT_SERVING',
        })}
        status="PENDING_REVIEW"
      />,
    );
    expect(screen.getByRole('status')).toHaveTextContent(/not enough review history yet/i);
  });

  it('falls back to a generic message for an unknown skip token', () => {
    render(
      <ApprovalPredictionPanel
        prediction={prediction({ probability: null, skipped: true, skipped_reason: 'SOMETHING_NEW' })}
        status="PENDING_REVIEW"
      />,
    );
    const notice = screen.getByRole('status');
    expect(notice).toHaveTextContent(/no approval likelihood is available/i);
    expect(notice).not.toHaveTextContent('SOMETHING_NEW');
  });

  it('falls back to the skipped notice when a non-sentinel row carries no probability', () => {
    render(
      <ApprovalPredictionPanel
        prediction={prediction({ probability: null })}
        status="PENDING_REVIEW"
      />,
    );
    expect(screen.getByRole('status')).toHaveTextContent(/no approval likelihood is available/i);
  });
});
