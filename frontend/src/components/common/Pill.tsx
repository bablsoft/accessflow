import type { CSSProperties, HTMLAttributes, ReactNode } from 'react';
import './pill.css';

interface PillProps extends Omit<HTMLAttributes<HTMLSpanElement>, 'children' | 'style'> {
  fg?: string;
  bg?: string;
  border?: string;
  withDot?: boolean;
  children: ReactNode;
  style?: CSSProperties;
  size?: 'sm' | 'md';
}

export function Pill({
  fg, bg, border, withDot, children, style, size = 'md', className, ...rest
}: PillProps) {
  const css: CSSProperties = {
    color: fg,
    background: bg,
    borderColor: border,
    ...style,
  };
  return (
    <span {...rest} className={`af-pill af-pill-${size}${className ? ` ${className}` : ''}`} style={css}>
      {withDot && <span className="af-pill-dot" />}
      {children}
    </span>
  );
}
