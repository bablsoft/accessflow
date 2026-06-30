import { Button, Input, Space } from 'antd';
import { DeleteOutlined, PlusOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import type { KeyValuePair } from '@/utils/apiRequestComposition';

interface KeyValueEditorProps {
  pairs: KeyValuePair[];
  onChange: (pairs: KeyValuePair[]) => void;
  keyPlaceholder?: string;
  valuePlaceholder?: string;
  addLabel?: string;
  disabled?: boolean;
}

/** Add/remove rows of key/value pairs. Reused for request headers, query params, and form fields. */
export function KeyValueEditor({
  pairs,
  onChange,
  keyPlaceholder,
  valuePlaceholder,
  addLabel,
  disabled,
}: KeyValueEditorProps) {
  const { t } = useTranslation();

  const update = (index: number, patch: Partial<KeyValuePair>) => {
    onChange(pairs.map((p, i) => (i === index ? { ...p, ...patch } : p)));
  };

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
      {pairs.map((pair, index) => (
        <Space key={index} style={{ width: '100%' }} align="start">
          <Input
            style={{ width: 220 }}
            value={pair.key}
            disabled={disabled}
            placeholder={keyPlaceholder ?? t('apiGov.editor.keyPlaceholder')}
            aria-label={t('apiGov.editor.keyPlaceholder')}
            onChange={(e) => update(index, { key: e.target.value })}
          />
          <Input
            style={{ width: 280 }}
            value={pair.value}
            disabled={disabled}
            placeholder={valuePlaceholder ?? t('apiGov.editor.valuePlaceholder')}
            aria-label={t('apiGov.editor.valuePlaceholder')}
            onChange={(e) => update(index, { value: e.target.value })}
          />
          {!disabled && (
            <Button
              icon={<DeleteOutlined />}
              aria-label={t('common.remove')}
              onClick={() => onChange(pairs.filter((_, i) => i !== index))}
            />
          )}
        </Space>
      ))}
      {!disabled && (
        <Button
          icon={<PlusOutlined />}
          onClick={() => onChange([...pairs, { key: '', value: '' }])}
          style={{ alignSelf: 'flex-start' }}
        >
          {addLabel ?? t('apiGov.editor.addRow')}
        </Button>
      )}
    </div>
  );
}
