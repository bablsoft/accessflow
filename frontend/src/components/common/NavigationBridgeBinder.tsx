import { useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { setNavigate } from '@/utils/navigationBridge';

// Binds React Router's navigate function to the module-level navigationBridge
// so non-React callers (Axios interceptor) can drive SPA-level redirects
// without a full page reload — keeps the AntD message portal mounted across
// the route change so toasts remain visible.
export function NavigationBridgeBinder(): null {
  const navigate = useNavigate();
  useEffect(() => {
    setNavigate(navigate);
    return () => setNavigate(null);
  }, [navigate]);
  return null;
}
