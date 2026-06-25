import type { ReactNode } from 'react';
import { Card } from 'antd';
import { DownOutlined, HolderOutlined, RightOutlined } from '@ant-design/icons';
import { useSortable } from '@dnd-kit/sortable';
import { CSS } from '@dnd-kit/utilities';
import { useTranslation } from 'react-i18next';
import type { DashboardWidgetId } from '@/store/preferencesStore';

interface DashboardWidgetCardProps {
  id: DashboardWidgetId;
  title: string;
  badge?: number;
  collapsed: boolean;
  onToggleCollapsed: () => void;
  children: ReactNode;
}

/**
 * A drag-sortable, collapsible card hosting a dashboard widget (AF-498). The drag handle is the only
 * draggable affordance so inner buttons/links stay clickable; collapse hides the body but keeps the
 * card in the layout. Keyboard-accessible via dnd-kit's keyboard sensor on the handle.
 */
export function DashboardWidgetCard({
  id,
  title,
  badge,
  collapsed,
  onToggleCollapsed,
  children,
}: DashboardWidgetCardProps) {
  const { t } = useTranslation();
  const { attributes, listeners, setNodeRef, transform, transition, isDragging } = useSortable({ id });

  const style = {
    transform: CSS.Transform.toString(transform),
    transition,
    opacity: isDragging ? 0.6 : 1,
  };

  return (
    <div ref={setNodeRef} style={style} data-testid={`dashboard-widget-${id}`}>
      <Card
        styles={{ body: { padding: collapsed ? 0 : 16 } }}
        title={
          <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
            <button
              type="button"
              className="af-icon-btn"
              aria-label={t('dashboard.drag_handle', { title })}
              style={{ cursor: 'grab', touchAction: 'none' }}
              {...attributes}
              {...listeners}
            >
              <HolderOutlined />
            </button>
            <button
              type="button"
              className="af-icon-btn"
              aria-label={collapsed ? t('dashboard.expand') : t('dashboard.collapse')}
              aria-expanded={!collapsed}
              onClick={onToggleCollapsed}
            >
              {collapsed ? <RightOutlined /> : <DownOutlined />}
            </button>
            <span style={{ fontWeight: 600 }}>{title}</span>
            {typeof badge === 'number' && badge > 0 && (
              <span className="af-sidebar-badge mono" style={{ marginLeft: 4 }}>
                {badge}
              </span>
            )}
          </div>
        }
      >
        {!collapsed && children}
      </Card>
    </div>
  );
}
