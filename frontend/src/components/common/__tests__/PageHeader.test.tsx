import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import '@/i18n';
import { PageHeader } from '../PageHeader';

describe('PageHeader', () => {
  it('renders the title', () => {
    render(<PageHeader title="Users" />);
    expect(screen.getByRole('heading', { name: 'Users' })).toBeInTheDocument();
  });

  it('renders the subtitle when given', () => {
    render(<PageHeader title="Users" subtitle="12 users" />);
    expect(screen.getByText('12 users')).toBeInTheDocument();
  });

  it('renders breadcrumbs and actions when given', () => {
    render(
      <PageHeader
        title="Group detail"
        breadcrumbs={['Groups', 'Engineering']}
        actions={<button type="button">Delete</button>}
      />,
    );
    expect(screen.getByText('Groups')).toBeInTheDocument();
    expect(screen.getByText('Engineering')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Delete' })).toBeInTheDocument();
  });

  it('renders no docs link when docsAnchor is omitted', () => {
    render(<PageHeader title="Users" actions={<button type="button">New</button>} />);
    expect(screen.queryByRole('link')).not.toBeInTheDocument();
  });

  it('links to the matching docs section when docsAnchor is given', () => {
    render(<PageHeader title="Users" docsAnchor="cfg-users" />);
    const link = screen.getByRole('link', { name: /view docs/i });
    expect(link).toHaveAttribute('href', 'https://accessflow.bablsoft.com/docs/#cfg-users');
  });

  it('opens the docs in a new tab without leaking the opener', () => {
    render(<PageHeader title="SAML" docsAnchor="cfg-saml" />);
    const link = screen.getByRole('link', { name: /view docs/i });
    expect(link).toHaveAttribute('target', '_blank');
    expect(link).toHaveAttribute('rel', 'noopener noreferrer');
  });

  it('renders the docs link on a page with no actions', () => {
    render(<PageHeader title="SAML" docsAnchor="cfg-saml" />);
    expect(screen.getByRole('link', { name: /view docs/i })).toBeInTheDocument();
  });

  it('places the docs link before the actions so it never competes with the primary CTA', () => {
    render(
      <PageHeader
        title="Users"
        docsAnchor="cfg-users"
        actions={<button type="button">New user</button>}
      />,
    );
    const link = screen.getByRole('link', { name: /view docs/i });
    const action = screen.getByRole('button', { name: 'New user' });
    const position = link.compareDocumentPosition(action);
    expect(position & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  });
});
