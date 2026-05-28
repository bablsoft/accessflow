import { describe, expect, it } from 'vitest';
import { render } from '@testing-library/react';
import '@/i18n';
import { DeltaBadge } from '../DeltaBadge';

describe('DeltaBadge', () => {
  it('renders nothing when delta is null', () => {
    const { container } = render(<DeltaBadge delta={null} />);
    expect(container).toBeEmptyDOMElement();
  });

  it('renders a "no change" pill when delta is zero', () => {
    const { getByText } = render(<DeltaBadge delta={0} />);
    expect(getByText(/no change/i)).toBeInTheDocument();
  });

  it('renders a signed positive number with the up arrow', () => {
    const { container, getByText } = render(<DeltaBadge delta={12} previous={20} />);
    expect(getByText(/\+12/)).toBeInTheDocument();
    expect(container.querySelector('.anticon-arrow-up')).not.toBeNull();
  });

  it('renders a signed negative number with the down arrow', () => {
    const { container, getByText } = render(<DeltaBadge delta={-7} previous={20} />);
    expect(getByText(/^-7/)).toBeInTheDocument();
    expect(container.querySelector('.anticon-arrow-down')).not.toBeNull();
  });

  it('appends the unit suffix when supplied', () => {
    const { getByText } = render(<DeltaBadge delta={3} unit="ms" />);
    expect(getByText(/\+3 ms/)).toBeInTheDocument();
  });

  it('uses muted styling when delta magnitude is below the absolute threshold', () => {
    // absThreshold=10 means a delta of 5 (absolute) is "small"; styling stays neutral.
    const { container } = render(
      <DeltaBadge delta={5} previous={100} absThreshold={10} />,
    );
    const pill = container.querySelector('.af-pill') as HTMLElement | null;
    expect(pill).not.toBeNull();
    expect(pill!.style.color).toContain('--fg-muted');
  });
});
