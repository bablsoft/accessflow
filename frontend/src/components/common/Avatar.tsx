import type { CSSProperties } from 'react';
import './common.css';

interface AvatarProps {
  name: string;
  size?: number;
}

export function Avatar({ name, size = 24 }: AvatarProps) {
  const initials = (name || '?')
    .split(' ')
    .map((s) => s[0])
    .filter(Boolean)
    .slice(0, 2)
    .join('')
    .toUpperCase();
  const hue = (name || '')
    .split('')
    .reduce((a, c) => a + c.charCodeAt(0), 0) % 360;
  const vars = { '--avatar-size': `${size}px`, '--avatar-hue': hue } as CSSProperties;
  return (
    <span className="af-avatar" style={vars}>
      {initials}
    </span>
  );
}
