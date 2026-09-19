import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import '@/i18n';
import { OnBehalfOfTag } from '../OnBehalfOfTag';

describe('OnBehalfOfTag', () => {
  it('renders nothing without any attribution', () => {
    const { container } = render(<OnBehalfOfTag email={null} displayName={null} userId="  " />);
    expect(container).toBeEmptyDOMElement();
  });

  it('prefers the display name, then the email, then the raw id', () => {
    const { rerender } = render(
      <OnBehalfOfTag displayName="Alice Engineer" email="alice@example.com" userId="u-1" />,
    );
    expect(screen.getByTestId('on-behalf-of-tag')).toHaveTextContent('on behalf of Alice Engineer');

    rerender(<OnBehalfOfTag email="alice@example.com" userId="u-1" />);
    expect(screen.getByTestId('on-behalf-of-tag')).toHaveTextContent('on behalf of alice@example.com');

    rerender(<OnBehalfOfTag userId="u-1" />);
    expect(screen.getByTestId('on-behalf-of-tag')).toHaveTextContent('on behalf of u-1');
  });

  it('honours a custom test id', () => {
    render(<OnBehalfOfTag email="bob@example.com" testId="header-obo" />);
    expect(screen.getByTestId('header-obo')).toBeInTheDocument();
  });
});
