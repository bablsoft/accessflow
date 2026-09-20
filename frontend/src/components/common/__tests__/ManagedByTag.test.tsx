import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import '@/i18n';
import { ManagedByTag } from '../ManagedByTag';

describe('ManagedByTag', () => {
  it('localizes both sources and never shows the raw enum', () => {
    const { rerender } = render(<ManagedByTag managedBy="BOOTSTRAP" />);
    expect(screen.getByTestId('managed-by-tag')).toHaveTextContent('Bootstrap');
    expect(screen.queryByText('BOOTSTRAP')).not.toBeInTheDocument();

    rerender(<ManagedByTag managedBy="UI" size="md" />);
    expect(screen.getByTestId('managed-by-tag')).toHaveTextContent('UI');
  });
});
