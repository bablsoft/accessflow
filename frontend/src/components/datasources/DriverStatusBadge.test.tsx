import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import { DriverStatusBadge } from './DriverStatusBadge';

describe('DriverStatusBadge', () => {
  it('renders the ready label for READY', () => {
    render(<DriverStatusBadge status="READY" />);
    expect(screen.getByText('Driver ready')).toBeInTheDocument();
  });

  it('renders the available label for AVAILABLE', () => {
    render(<DriverStatusBadge status="AVAILABLE" />);
    expect(screen.getByText('Will download')).toBeInTheDocument();
  });

  it('renders the unavailable label for UNAVAILABLE', () => {
    render(<DriverStatusBadge status="UNAVAILABLE" />);
    expect(screen.getByText('Unavailable')).toBeInTheDocument();
  });
});
