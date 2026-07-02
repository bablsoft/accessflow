import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import { DetailCard } from '../DetailCard';

describe('DetailCard', () => {
  it('renders the title and children', () => {
    render(
      <DetailCard title="Section title">
        <div>body content</div>
      </DetailCard>,
    );
    expect(screen.getByText('Section title')).toBeInTheDocument();
    expect(screen.getByText('body content')).toBeInTheDocument();
  });

  it('renders the icon and extra slots when provided', () => {
    render(
      <DetailCard
        title="With slots"
        icon={<span data-testid="card-icon" />}
        extra={<span data-testid="card-extra">extra</span>}
      >
        <div>body</div>
      </DetailCard>,
    );
    expect(screen.getByTestId('card-icon')).toBeInTheDocument();
    expect(screen.getByTestId('card-extra')).toBeInTheDocument();
  });

  it('omits the icon wrapper when no icon is given', () => {
    const { container } = render(
      <DetailCard title="No icon">
        <div>body</div>
      </DetailCard>,
    );
    expect(container.querySelectorAll('span')).toHaveLength(1);
  });
});
