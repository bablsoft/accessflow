interface LogoMarkProps {
  size?: number;
  className?: string;
  accentColor?: string;
}

export function LogoMark({
  size = 24,
  className,
  accentColor = 'oklch(0.82 0.17 145)',
}: LogoMarkProps) {
  return (
    <svg
      className={className}
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill="none"
      aria-hidden="true"
      focusable="false"
      data-testid="af-logo-mark"
    >
      <rect x="2" y="3" width="20" height="6" rx="1" stroke="currentColor" strokeWidth="1.6" />
      <rect x="2" y="15" width="20" height="6" rx="1" stroke={accentColor} strokeWidth="1.6" />
      <path d="M8 9v2M12 9v2M16 9v2" stroke="currentColor" strokeWidth="1.6" />
      <path d="M8 13v2M12 13v2M16 13v2" stroke={accentColor} strokeWidth="1.6" />
    </svg>
  );
}
