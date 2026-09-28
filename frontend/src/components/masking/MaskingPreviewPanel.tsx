import { Input, Typography } from 'antd';
import { useTranslation } from 'react-i18next';
import type { MaskingStrategy } from '@/types/api';
import { maskingPreview } from '@/utils/maskingPreview';

interface MaskingPreviewPanelProps {
  strategy: MaskingStrategy | undefined;
  params: Record<string, string> | undefined;
  sample: string;
  onSampleChange: (value: string) => void;
  label: string;
  sampleAriaLabel: string;
}

/** Live preview of a masking strategy applied to an editable sample value. */
export function MaskingPreviewPanel({
  strategy,
  params,
  sample,
  onSampleChange,
  label,
  sampleAriaLabel,
}: MaskingPreviewPanelProps) {
  const { t } = useTranslation();
  const preview = maskingPreview(strategy ?? 'FULL', sample, params);
  return (
    <div style={{ padding: '10px 12px', background: 'var(--bg-sunken)', borderRadius: 6 }}>
      <div className="muted" style={{ fontSize: 11, marginBottom: 6 }}>
        {label}
      </div>
      <Input
        size="small"
        value={sample}
        onChange={(e) => onSampleChange(e.target.value)}
        aria-label={sampleAriaLabel}
        style={{ marginBottom: 8 }}
      />
      <Typography.Text
        className="mono"
        data-testid="masking-preview-output"
        copyable={preview != null && preview !== ''}
      >
        {preview == null ? t('maskingParams.preview_null') : preview === '' ? '—' : preview}
      </Typography.Text>
      {strategy === 'REGEX_REPLACE' && (
        <div className="muted" style={{ fontSize: 11, marginTop: 6 }}>
          {t('maskingParams.preview_regex_note')}
        </div>
      )}
    </div>
  );
}
