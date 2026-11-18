import { Skeleton } from 'antd';
import './layout.css';

/** Shown while a lazily loaded route's chunk is fetched. */
export function RouteFallback() {
  return (
    <div className="af-route-fallback" aria-busy="true">
      <Skeleton active title={{ width: 220 }} paragraph={{ rows: 6 }} />
    </div>
  );
}
