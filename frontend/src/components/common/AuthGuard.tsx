import type { ReactNode } from 'react';
import { Navigate } from 'react-router-dom';
import { useAuthStore } from '@/store/authStore';
import type { Role } from '@/types/api';

interface GuardProps {
  children: ReactNode;
  requireRole?: Role | Role[];
}

export function AuthGuard({ children, requireRole }: GuardProps) {
  const user = useAuthStore((s) => s.user());
  if (!user) return <Navigate to="/login" replace />;
  if (requireRole) {
    const roles = Array.isArray(requireRole) ? requireRole : [requireRole];
    if (!roles.includes(user.role)) {
      return <Navigate to="/editor" replace />;
    }
  }
  return <>{children}</>;
}
