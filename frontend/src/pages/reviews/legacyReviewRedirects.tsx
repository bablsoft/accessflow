import { Navigate, useSearchParams } from 'react-router-dom';
import { reviewHubPath } from '@/utils/reviewHubTabs';

/**
 * Pre-#772 queue routes. Notifications, bookmarks and the public docs linked `/api-reviews` and
 * `/deployment-reviews[?tab=rollbacks]` directly, so they keep resolving — into the matching tab
 * of the unified `/reviews` hub. `replace` keeps the legacy URL out of the history stack.
 */
export function ApiReviewsRedirect() {
  return <Navigate to={reviewHubPath('api')} replace />;
}

export function LegacyDeploymentReviewsRedirect() {
  const [searchParams] = useSearchParams();
  const tab = searchParams.get('tab') === 'rollbacks' ? 'rollbacks' : 'deployments';
  return <Navigate to={reviewHubPath(tab)} replace />;
}
