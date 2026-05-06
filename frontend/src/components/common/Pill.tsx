import type { CSSProperties, ReactNode } from 'react';
import './pill.css';

interface PillProps {
  fg?: string;
  bg?: string;
  border?: string;
  withDot?: boolean;
  children: ReactNode;
  style?: CSSProperties;
  size?: 'sm' | 'md';
}

export function Pill({ fg, bg, border, withDot, children, style, size = 'md' }: PillProps) {
  const css: CSSProperties = {
    color: fg,
    background: bg,
    borderColor: border,
    ...style,
  };
  return (
    <span className={`af-pill af-pill-${size}`} style={css}>
      {withDot && <span className="af-pill-dot" />}
      {children}
    </span>
  );
}
