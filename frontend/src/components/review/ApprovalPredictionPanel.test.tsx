import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import '@/i18n';
import type { ApprovalPredictionDetail, ApprovalPredictionSkipReason } from '@/types/api';
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
  it('shows a pending message during the async scoring gap', () => {
    render(
      <ApprovalPredictionPanel
        prediction={null}
        status="PENDING_REVIEW"
        updatedAt={new Date(Date.now() - 5_000).toISOString()}
      />,
    );
    expect(screen.getByText(/computing the approval likelihood/i)).toBeInTheDocument();
  });

  it('shows a pending message while the query has not reached review yet', () => {
    render(<ApprovalPredictionPanel prediction={null} status="PENDING_AI" />);
    expect(screen.getByText(/computing the approval likelihood/i)).toBeInTheDocument();
  });

  it('stops promising a pending score once the grace window has elapsed', () => {
    render(
      <ApprovalPredictionPanel
        prediction={null}
        status="PENDING_REVIEW"
        updatedAt="2026-05-01T10:00:00Z"
      />,
    );
    expect(screen.getByText(/no approval likelihood is available/i)).toBeInTheDocument();
  });

  it('keeps the pending copy when the timestamp is unusable', () => {
    render(
      <ApprovalPredictionPanel prediction={null} status="PENDING_REVIEW" updatedAt="not-a-date" />,
    );
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

  it('falls back to a generic message for a token the client does not know', () => {
    // The type union is closed, but the server can ship a new token ahead of the frontend —
    // the raw machine token must never reach the DOM.
    const unknownToken = 'SOMETHING_NEW' as ApprovalPredictionSkipReason;
    render(
      <ApprovalPredictionPanel
        prediction={prediction({ probability: null, skipped: true, skipped_reason: unknownToken })}
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
