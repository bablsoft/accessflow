import type { ReactNode } from 'react';
import { SurfaceCard } from '@/components/layout/SurfaceCard';

export function DetailCard({
  title, icon, extra, children,
}: { title: string; icon?: ReactNode; extra?: ReactNode; children: ReactNode }) {
  return (
    <SurfaceCard title={title} icon={icon} extra={extra}>
      {children}
    </SurfaceCard>
  );
}
