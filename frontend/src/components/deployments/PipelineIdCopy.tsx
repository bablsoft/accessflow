import { Typography } from 'antd';
import { useTranslation } from 'react-i18next';

interface PipelineIdCopyProps {
  /** The pipeline UUID — the only identifier the trigger API accepts, so CI setup needs it verbatim. */
  id: string;
  /** Render just the leading segment; the copy still carries the whole id. */
  truncate?: boolean;
}

/**
 * A copyable pipeline id. AntD derives the copy button's accessible name from the tooltip
 * strings, so the t()-keyed label goes on `tooltips`, not on an `aria-label` prop.
 */
export function PipelineIdCopy({ id, truncate = false }: PipelineIdCopyProps) {
  const { t } = useTranslation();
  return (
    <Typography.Text
      className="mono"
      style={{ fontSize: 12 }}
      data-testid="pipeline-id"
      copyable={{
        text: id,
        tooltips: [t('deploygov.pipelines.copyId'), t('deploygov.pipelines.copiedId')],
      }}
      // The pipeline list makes whole rows clickable; copying an id must not also navigate.
      onClick={(e) => e.stopPropagation()}
    >
      {truncate ? `${id.slice(0, 8)}…` : id}
    </Typography.Text>
  );
}
