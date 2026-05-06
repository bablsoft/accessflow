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
  return (
    <span
      style={{
        display: 'inline-flex',
        alignItems: 'center',
        justifyContent: 'center',
        width: size,
        height: size,
        borderRadius: '50%',
        background: `oklch(0.92 0.06 ${hue})`,
        color: `oklch(0.35 0.12 ${hue})`,
        fontSize: size * 0.42,
        fontWeight: 600,
        flexShrink: 0,
        fontFamily: 'var(--font-sans)',
      }}
    >
      {initials}
    </span>
  );
}
