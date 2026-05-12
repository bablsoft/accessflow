import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import { DatasourceWizardSteps } from './DatasourceWizardSteps';

describe('DatasourceWizardSteps', () => {
  it('renders four step labels', () => {
    render(<DatasourceWizardSteps current="type" />);
    expect(screen.getByText('Database type')).toBeInTheDocument();
    expect(screen.getByText('Connection details')).toBeInTheDocument();
    expect(screen.getByText('Connection test')).toBeInTheDocument();
    expect(screen.getByText('Configuration')).toBeInTheDocument();
  });

  it('marks the connection step as current via aria-current', () => {
    const { container } = render(<DatasourceWizardSteps current="connection" />);
    const current = container.querySelector('.ant-steps-item-active');
    expect(current?.textContent).toContain('Connection details');
  });

  it('marks the settings step as current', () => {
    const { container } = render(<DatasourceWizardSteps current="settings" />);
    const current = container.querySelector('.ant-steps-item-active');
    expect(current?.textContent).toContain('Configuration');
  });
});
