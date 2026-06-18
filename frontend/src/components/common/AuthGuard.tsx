import type { ReactNode } from 'react';
import { Navigate } from 'react-router-dom';
import { useAuthStore } from '@/store/authStore';
import type { Role } from '@/types/api';

interface GuardProps {
  children: ReactNode;
  requireRole?: Role | Role[];
  requirePlatformAdmin?: boolean;
}

export function AuthGuard({ children, requireRole, requirePlatformAdmin }: GuardProps) {
  const user = useAuthStore((s) => s.user);
  if (!user) return <Navigate to="/login" replace />;
  if (requirePlatformAdmin && !user.platform_admin) {
    return <Navigate to="/editor" replace />;
  }
  if (requireRole) {
    const roles = Array.isArray(requireRole) ? requireRole : [requireRole];
    if (!roles.includes(user.role)) {
      return <Navigate to="/editor" replace />;
    }
  }
  return <>{children}</>;
}
