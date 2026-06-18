import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import { PresenceBar } from './PresenceBar';
import type { CollabMember } from '@/types/ws';

const members: CollabMember[] = [
  { user_id: 'u-1', display_name: 'Ann Analyst', color: '#2563eb' },
  { user_id: 'u-2', display_name: 'Bob', color: '#dc2626' },
];

describe('PresenceBar', () => {
  it('renders an avatar per collaborator with an accessible label', () => {
    render(<PresenceBar members={members} />);
    expect(screen.getByLabelText('Ann Analyst')).toBeInTheDocument();
    expect(screen.getByLabelText('Bob')).toBeInTheDocument();
  });

  it('renders nothing when no one is present', () => {
    const { container } = render(<PresenceBar members={[]} />);
    expect(container).toBeEmptyDOMElement();
  });
});
