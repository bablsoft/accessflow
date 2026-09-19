import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import '@/i18n';
import { PrincipalTypeTag } from '../PrincipalTypeTag';
import { renderUserOption } from '../renderUserOption';

describe('PrincipalTypeTag', () => {
  it('renders nothing for a person or an unknown principal', () => {
    const { container } = render(
      <>
        <PrincipalTypeTag principalType="HUMAN" />
        <PrincipalTypeTag principalType={null} />
        <PrincipalTypeTag />
      </>,
    );
    expect(container).toBeEmptyDOMElement();
  });

  it('badges a service account with a localized label, never the raw enum', () => {
    render(<PrincipalTypeTag principalType="SERVICE_ACCOUNT" />);
    expect(screen.getByTestId('principal-type-tag')).toHaveTextContent('Service account');
    expect(screen.queryByText('SERVICE_ACCOUNT')).not.toBeInTheDocument();
  });

  it('renderUserOption shows the label and badges only service accounts', () => {
    const { rerender } = render(
      renderUserOption({ data: { value: 'u-1', label: 'Bot (bot@example.com)', principal_type: 'SERVICE_ACCOUNT' } }),
    );
    expect(screen.getByText('Bot (bot@example.com)')).toBeInTheDocument();
    expect(screen.getByTestId('principal-type-tag')).toBeInTheDocument();

    rerender(
      renderUserOption({ data: { value: 'u-2', label: 'Alice (alice@example.com)', principal_type: 'HUMAN' } }),
    );
    expect(screen.getByText('Alice (alice@example.com)')).toBeInTheDocument();
    expect(screen.queryByTestId('principal-type-tag')).not.toBeInTheDocument();
  });
});
