import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import { DriverStatusBadge } from './DriverStatusBadge';

describe('DriverStatusBadge', () => {
  it('renders the bundled label for READY + bundled', () => {
    render(<DriverStatusBadge status="READY" bundled />);
    expect(screen.getByText('Bundled')).toBeInTheDocument();
  });

  it('renders the downloaded label for READY + not bundled', () => {
    render(<DriverStatusBadge status="READY" bundled={false} />);
    expect(screen.getByText('Downloaded')).toBeInTheDocument();
  });

  it('renders the available label for AVAILABLE', () => {
    render(<DriverStatusBadge status="AVAILABLE" />);
    expect(screen.getByText('Will download')).toBeInTheDocument();
  });

  it('renders the unavailable label for UNAVAILABLE regardless of bundled', () => {
    render(<DriverStatusBadge status="UNAVAILABLE" bundled />);
    expect(screen.getByText('Unavailable')).toBeInTheDocument();
  });

  it('defaults to downloaded label when bundled prop is omitted on READY', () => {
    render(<DriverStatusBadge status="READY" />);
    expect(screen.getByText('Downloaded')).toBeInTheDocument();
  });
});
