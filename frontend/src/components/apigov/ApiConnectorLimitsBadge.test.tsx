import { describe, expect, it } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import '@/i18n';
import { ApiConnectorLimitsBadge } from './ApiConnectorLimitsBadge';

describe('ApiConnectorLimitsBadge', () => {
  it('renders a dash when all five fields are unset', () => {
    render(<ApiConnectorLimitsBadge connector={{}} />);
    expect(screen.getByText('—')).toBeInTheDocument();
    expect(screen.queryByTestId('connector-limits-badge')).not.toBeInTheDocument();
  });

  it('renders whichever rate limits are set', () => {
    render(<ApiConnectorLimitsBadge connector={{ calls_per_day: 500 }} />);
    expect(screen.getByTestId('connector-limits-badge')).toHaveTextContent('500 / day');
  });

  it('names the share, price and budget in the tooltip', async () => {
    render(
      <ApiConnectorLimitsBadge
        connector={{
          calls_per_minute: 1,
          per_user_share_percent: 20,
          cost_per_call_micros: 500_000,
          monthly_budget_micros: 10_000_000,
        }}
      />,
    );
    fireEvent.mouseEnter(screen.getByTestId('connector-limits-badge'));
    expect(await screen.findByText('Per-user share: 20%')).toBeInTheDocument();
    expect(screen.getByText('Cost per call: 0.5')).toBeInTheDocument();
    expect(screen.getByText('Monthly budget: 10')).toBeInTheDocument();
  });

  it('says unlimited for an unset share and budget', async () => {
    render(<ApiConnectorLimitsBadge connector={{ cost_per_call_micros: 1 }} />);
    expect(screen.getByTestId('connector-limits-badge')).toHaveTextContent('No rate limit');
    fireEvent.mouseEnter(screen.getByTestId('connector-limits-badge'));
    expect(await screen.findByText('Per-user share: unlimited')).toBeInTheDocument();
    expect(screen.getByText('Monthly budget: unlimited')).toBeInTheDocument();
  });
});
