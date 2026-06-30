import { Button, Input, Radio, Segmented, Space, Tabs, Upload } from 'antd';
import { DeleteOutlined, PlusOutlined, UploadOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import type { ApiBodyType } from '@/types/api';
import { KeyValueEditor } from './KeyValueEditor';
import { API_BODY_TYPES, apiBodyTypeLabel } from '@/utils/enumLabels';
import {
  MAX_BODY_BYTES,
  fileToBase64,
  type ApiRequestComposition,
  type FormFieldRow,
} from '@/utils/apiRequestComposition';

interface Props {
  value: ApiRequestComposition;
  onChange: (next: ApiRequestComposition) => void;
  defaultHeaders: Record<string, string>;
  onTooLarge: () => void;
}

export function ApiRequestComposer({ value, onChange, defaultHeaders, onTooLarge }: Props) {
  const { t } = useTranslation();
  const patch = (p: Partial<ApiRequestComposition>) => onChange({ ...value, ...p });

  const defaultHeaderPairs = Object.entries(defaultHeaders).map(([key, v]) => ({ key, value: v }));

  const updateFormField = (index: number, p: Partial<FormFieldRow>) =>
    patch({ formFields: value.formFields.map((f, i) => (i === index ? { ...f, ...p } : f)) });

  const readFile = async (file: File, apply: (base64: string) => void) => {
    if (file.size > MAX_BODY_BYTES) {
      onTooLarge();
      return false;
    }
    apply(await fileToBase64(file));
    return false;
  };

  const bodyTab = (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
      <Radio.Group
        value={value.bodyType}
        onChange={(e) => patch({ bodyType: e.target.value as ApiBodyType })}
        optionType="button"
        buttonStyle="solid"
        options={API_BODY_TYPES.map((b) => ({ value: b, label: apiBodyTypeLabel(t, b) }))}
      />
      {value.bodyType === 'RAW' && (
        <>
          <Input
            value={value.contentType}
            onChange={(e) => patch({ contentType: e.target.value })}
            aria-label={t('apiGov.editor.contentType')}
            placeholder={t('apiGov.editor.contentType')}
          />
          <Input.TextArea rows={8} value={value.rawBody} onChange={(e) => patch({ rawBody: e.target.value })} />
        </>
      )}
      {value.bodyType === 'FORM_URLENCODED' && (
        <KeyValueEditor
          pairs={value.formFields.map((f) => ({ key: f.key, value: f.value }))}
          onChange={(pairs) =>
            patch({ formFields: pairs.map((p) => ({ key: p.key, type: 'TEXT', value: p.value })) })
          }
        />
      )}
      {value.bodyType === 'FORM_DATA' && (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
          {value.formFields.map((field, index) => (
            <Space key={index} align="start">
              <Input
                style={{ width: 200 }}
                value={field.key}
                placeholder={t('apiGov.editor.keyPlaceholder')}
                aria-label={t('apiGov.editor.keyPlaceholder')}
                onChange={(e) => updateFormField(index, { key: e.target.value })}
              />
              <Segmented
                value={field.type}
                onChange={(v) => updateFormField(index, { type: v as 'TEXT' | 'FILE', value: '' })}
                options={[
                  { value: 'TEXT', label: t('apiGov.editor.fieldTypeText') },
                  { value: 'FILE', label: t('apiGov.editor.fieldTypeFile') },
                ]}
              />
              {field.type === 'TEXT' ? (
                <Input
                  style={{ width: 260 }}
                  value={field.value}
                  placeholder={t('apiGov.editor.valuePlaceholder')}
                  aria-label={t('apiGov.editor.valuePlaceholder')}
                  onChange={(e) => updateFormField(index, { value: e.target.value })}
                />
              ) : (
                <Upload
                  maxCount={1}
                  showUploadList={false}
                  beforeUpload={(file) =>
                    readFile(file, (base64) =>
                      updateFormField(index, {
                        value: base64,
                        filename: file.name,
                        content_type: file.type || 'application/octet-stream',
                      }),
                    )
                  }
                >
                  <Button icon={<UploadOutlined />}>{field.filename ?? t('apiGov.editor.chooseFile')}</Button>
                </Upload>
              )}
              <Button
                icon={<DeleteOutlined />}
                aria-label={t('common.remove')}
                onClick={() => patch({ formFields: value.formFields.filter((_, i) => i !== index) })}
              />
            </Space>
          ))}
          <Button
            icon={<PlusOutlined />}
            style={{ alignSelf: 'flex-start' }}
            onClick={() =>
              patch({ formFields: [...value.formFields, { key: '', type: 'TEXT', value: '' }] })
            }
          >
            {t('apiGov.editor.addRow')}
          </Button>
        </div>
      )}
      {value.bodyType === 'BINARY' && (
        <Upload
          maxCount={1}
          showUploadList={false}
          beforeUpload={(file) =>
            readFile(file, (base64) =>
              patch({
                binaryBase64: base64,
                binaryFilename: file.name,
                contentType: file.type || 'application/octet-stream',
              }),
            )
          }
        >
          <Button icon={<UploadOutlined />}>
            {value.binaryFilename ?? t('apiGov.editor.binaryFile')}
          </Button>
        </Upload>
      )}
    </div>
  );

  return (
    <Tabs
      items={[
        {
          key: 'params',
          label: t('apiGov.editor.tabParams'),
          children: <KeyValueEditor pairs={value.queryParams} onChange={(p) => patch({ queryParams: p })} />,
        },
        {
          key: 'headers',
          label: t('apiGov.editor.tabHeaders'),
          children: (
            <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
              <KeyValueEditor pairs={value.headers} onChange={(p) => patch({ headers: p })} />
              {defaultHeaderPairs.length > 0 && (
                <div>
                  <div className="muted" style={{ marginBottom: 4 }}>
                    {t('apiGov.editor.defaultHeaders')} · {t('apiGov.editor.defaultHeadersHint')}
                  </div>
                  <KeyValueEditor pairs={defaultHeaderPairs} onChange={() => undefined} disabled />
                </div>
              )}
            </div>
          ),
        },
        { key: 'body', label: t('apiGov.editor.tabBody'), children: bodyTab },
      ]}
    />
  );
}
