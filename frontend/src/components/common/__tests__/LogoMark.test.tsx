import { render, screen } from '@testing-library/react';
import { describe, it, expect } from 'vitest';
import { LogoMark } from '../LogoMark';

describe('LogoMark', () => {
  it('renders an svg with default size 24', () => {
    render(<LogoMark />);
    const svg = screen.getByTestId('af-logo-mark');
    expect(svg.tagName.toLowerCase()).toBe('svg');
    expect(svg.getAttribute('width')).toBe('24');
    expect(svg.getAttribute('height')).toBe('24');
  });

  it('honors the size prop', () => {
    render(<LogoMark size={48} />);
    const svg = screen.getByTestId('af-logo-mark');
    expect(svg.getAttribute('width')).toBe('48');
    expect(svg.getAttribute('height')).toBe('48');
  });

  it('is decorative (aria-hidden)', () => {
    render(<LogoMark />);
    expect(screen.getByTestId('af-logo-mark').getAttribute('aria-hidden')).toBe('true');
  });

  it('applies the custom accentColor to the second rect and lower path strokes', () => {
    const { container } = render(<LogoMark accentColor="#ff00ff" />);
    const rects = container.querySelectorAll('rect');
    expect(rects).toHaveLength(2);
    expect(rects.item(0)?.getAttribute('stroke')).toBe('currentColor');
    expect(rects.item(1)?.getAttribute('stroke')).toBe('#ff00ff');
  });

  it('passes className through', () => {
    render(<LogoMark className="custom-cls" />);
    expect(screen.getByTestId('af-logo-mark').getAttribute('class')).toBe('custom-cls');
  });
});
