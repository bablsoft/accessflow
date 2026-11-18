import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import * as m from 'motion/react-m';
import { MotionProvider } from '../MotionProvider';

describe('MotionProvider', () => {
  it('renders lazy motion components', () => {
    render(
      <MotionProvider>
        <m.div animate={{ opacity: 1 }}>content</m.div>
      </MotionProvider>,
    );
    expect(screen.getByText('content')).toBeInTheDocument();
  });
});
