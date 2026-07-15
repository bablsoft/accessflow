import type { ReactNode } from 'react';
import { Navigate } from 'react-router-dom';
import { useAuthStore } from '@/store/authStore';
import { hasAnyPermission, type Permission } from '@/utils/permissions';
import { homePathForUser } from '@/utils/homePath';

interface GuardProps {
  children: ReactNode;
  /** Require any of the given functional permissions (AF-522). */
  requirePermission?: Permission | Permission[];
  requirePlatformAdmin?: boolean;
}

export function AuthGuard({ children, requirePermission, requirePlatformAdmin }: GuardProps) {
  const user = useAuthStore((s) => s.user);
  if (!user) return <Navigate to="/login" replace />;
  const home = homePathForUser(user);
  if (requirePlatformAdmin && !user.platform_admin) {
    return <Navigate to={home} replace />;
  }
  if (requirePermission) {
    const permissions = Array.isArray(requirePermission) ? requirePermission : [requirePermission];
    if (!hasAnyPermission(user, permissions)) {
      return <Navigate to={home} replace />;
    }
  }
  return <>{children}</>;
}
