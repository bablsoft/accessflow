import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import '@/i18n';
import { ClientApplicationTag } from '../ClientApplicationTag';

describe('ClientApplicationTag', () => {
  it('renders nothing without an application name', () => {
    const { container } = render(<ClientApplicationTag name="  " source="HEADER" />);
    expect(container).toBeEmptyDOMElement();
  });

  it('shows an API-key name without the untrusted marker', () => {
    render(<ClientApplicationTag name="reporting" source="API_KEY" />);
    expect(screen.getByTestId('client-application')).toHaveTextContent('reporting');
    expect(screen.queryByTestId('client-application-untrusted')).not.toBeInTheDocument();
  });

  it('marks a header-supplied name as untrusted, whatever its case', () => {
    const { rerender } = render(<ClientApplicationTag name="notebook" source="HEADER" />);
    expect(screen.getByTestId('client-application-untrusted')).toHaveTextContent('Untrusted');

    rerender(<ClientApplicationTag name="notebook" source="header" testId="audit-app" />);
    expect(screen.getByTestId('audit-app-untrusted')).toBeInTheDocument();
  });
});
