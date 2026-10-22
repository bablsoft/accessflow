import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import '@/i18n';
import { ChangeTicketLink } from './ChangeTicketLink';

describe('ChangeTicketLink', () => {
  it('links the key out in a new tab without an opener and tags the decision', () => {
    render(
      <ChangeTicketLink
        ticket={{
          system: 'SERVICENOW',
          external_key: 'CHG0001',
          url: 'https://acme.service-now.com/x',
          status: 'Closed',
          decision: 'REJECTED',
        }}
      />,
    );

    const link = screen.getByText('CHG0001').closest('a');
    expect(link).toHaveAttribute('href', 'https://acme.service-now.com/x');
    expect(link).toHaveAttribute('target', '_blank');
    expect(link).toHaveAttribute('rel', 'noopener noreferrer');
    expect(screen.getByText('rejected')).toBeInTheDocument();
  });

  it('renders a ticket without a URL or decision as plain text', () => {
    render(
      <ChangeTicketLink ticket={{ system: 'JIRA', external_key: 'OPS-1', status: 'To Do' }} />,
    );

    expect(screen.getByText('OPS-1').closest('a')).toBeNull();
    expect(screen.queryByText('approved')).toBeNull();
    expect(screen.queryByText('rejected')).toBeNull();
  });
});
