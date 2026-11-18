import { Popover } from 'antd';
import { QuestionCircleOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import './triage.css';

interface TriageShortcutsProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  /** Leave out the decision keys on a queue that has none. */
  decisions?: boolean;
}

/** The "?" control next to a review queue, listing its keyboard shortcuts. */
export function TriageShortcuts({ open, onOpenChange, decisions = true }: TriageShortcutsProps) {
  const { t } = useTranslation();
  const rows: [string, string][] = [
    ['J / ↓', t('reviews.shortcuts.next')],
    ['K / ↑', t('reviews.shortcuts.previous')],
    ['Enter', t('reviews.shortcuts.open')],
    ...(decisions
      ? ([
          ['A', t('reviews.shortcuts.approve')],
          ['R', t('reviews.shortcuts.reject')],
        ] as [string, string][])
      : []),
    ['?', t('reviews.shortcuts.help')],
  ];
  return (
    <Popover
      trigger="click"
      placement="bottomRight"
      open={open}
      onOpenChange={onOpenChange}
      title={t('reviews.shortcuts.title')}
      content={
        <dl className="af-triage-shortcuts">
          {rows.map(([keys, label]) => (
            <div key={keys}>
              <dt><span className="kbd">{keys}</span></dt>
              <dd>{label}</dd>
            </div>
          ))}
        </dl>
      }
    >
      <button type="button" className="af-icon-btn" aria-label={t('reviews.shortcuts.title')}>
        <QuestionCircleOutlined />
      </button>
    </Popover>
  );
}
