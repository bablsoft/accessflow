import React from 'react';

export function DetailCard({
  title, icon, extra, children,
}: { title: string; icon?: React.ReactNode; extra?: React.ReactNode; children: React.ReactNode }) {
  return (
    <div
      style={{
        background: 'var(--bg-elev)',
        border: '1px solid var(--border)',
        borderRadius: 'var(--radius-md)',
      }}
    >
      <div
        style={{
          padding: '10px 14px',
          borderBottom: '1px solid var(--border)',
          display: 'flex',
          alignItems: 'center',
          gap: 8,
        }}
      >
        {icon && <span style={{ color: 'var(--fg-muted)' }}>{icon}</span>}
        <span style={{ fontWeight: 600, fontSize: 13 }}>{title}</span>
        {extra}
      </div>
      {children}
    </div>
  );
}
