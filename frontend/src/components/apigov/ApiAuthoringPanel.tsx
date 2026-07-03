import type { ReactNode } from 'react';
import { App, Button, Card, Input, Select, Space, Tag } from 'antd';
import { useTranslation } from 'react-i18next';
import { ApiRequestComposer } from '@/components/apigov/ApiRequestComposer';
import { riskLevelLabel } from '@/utils/enumLabels';
import type { ApiConnector } from '@/types/api';
import type { ApiAuthoring, ApiAuthoringValue } from './useApiAuthoring';

interface ApiAuthoringPanelProps {
  authoring: ApiAuthoring;
  connector: ApiConnector | null;
  value: ApiAuthoringValue;
  onChange: (next: ApiAuthoringValue) => void;
  /** 'drawer' stacks the risk-preview / text-to-API cards under the form (#559). */
  layout?: 'page' | 'drawer';
  /** Rendered above the operation picker — the hosts put the connector selector here. */
  header?: ReactNode;
  /** Rendered under the composer — the page puts schedule/justification/actions here. */
  footer?: ReactNode;
}

/**
 * The shared API-authoring surface (#559): operation picker (auto-fills verb/path), verb/path
 * inputs, the full request composer (params / headers / body types / form-data / file), the AI
 * risk-preview card, and the text-to-API card. Rendered by both the standalone API Editor page
 * and the group-builder member drawer so the two never drift.
 */
export function ApiAuthoringPanel({
  authoring,
  connector,
  value,
  onChange,
  layout = 'page',
  header,
  footer,
}: ApiAuthoringPanelProps) {
  const { t } = useTranslation();
  const { message } = App.useApp();

  const cards = (
    <>
      <Card size="small" title={t('apiGov.editor.riskPreview')}>
        {authoring.preview ? (
          <div>
            <Tag>
              {riskLevelLabel(t, authoring.preview.risk_level)} · {authoring.preview.risk_score}
            </Tag>
            <p style={{ marginTop: 8 }}>{authoring.preview.summary}</p>
            <ul style={{ paddingLeft: 18, margin: 0 }}>
              {authoring.preview.issues.map((issue, i) => (
                <li key={i} style={{ fontSize: 12 }}>{issue}</li>
              ))}
            </ul>
          </div>
        ) : (
          <span className="muted">{t('apiGov.editor.noPreview')}</span>
        )}
      </Card>

      {authoring.canTextToApi && (
        <Card size="small" title={t('apiGov.editor.textToApi')}>
          <Input.TextArea
            rows={3}
            value={authoring.prompt}
            onChange={(e) => authoring.setPrompt(e.target.value)}
            placeholder={t('apiGov.editor.textToApiHint')}
          />
          <Button
            style={{ marginTop: 8 }}
            onClick={authoring.generate}
            loading={authoring.generating}
            disabled={!authoring.prompt}
            block
          >
            {t('apiGov.editor.generate')}
          </Button>
        </Card>
      )}
    </>
  );

  return (
    <div
      style={
        layout === 'page'
          ? {
              flex: 1,
              overflow: 'auto',
              padding: 24,
              display: 'grid',
              gridTemplateColumns: '1fr 360px',
              gap: 20,
            }
          : { display: 'flex', flexDirection: 'column', gap: 14 }
      }
    >
      <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
        {header}

        {connector?.schema_present && (
          <label>
            <div className="muted" style={{ marginBottom: 4 }}>{t('apiGov.editor.operation')}</div>
            <Select
              style={{ width: '100%' }}
              allowClear
              showSearch
              optionFilterProp="label"
              placeholder={t('apiGov.editor.searchOperation')}
              value={value.operationId ?? undefined}
              onChange={authoring.selectOperation}
              options={authoring.operations.map((o) => ({
                value: o.operation_id,
                label: `${o.verb} ${o.path}${o.write ? ' (write)' : ''}`,
              }))}
            />
          </label>
        )}

        <Space.Compact style={{ width: '100%' }}>
          <Input
            style={{ width: 120 }}
            value={value.verb}
            onChange={(e) => onChange({ ...value, verb: e.target.value })}
            aria-label={t('apiGov.editor.verb')}
          />
          <Input
            value={value.requestPath}
            onChange={(e) => onChange({ ...value, requestPath: e.target.value })}
            placeholder="/v1/resource"
            aria-label={t('apiGov.editor.path')}
          />
        </Space.Compact>

        <ApiRequestComposer
          value={value.composition}
          onChange={(composition) => onChange({ ...value, composition })}
          defaultHeaders={connector?.default_headers ?? {}}
          onTooLarge={() => message.error(t('apiGov.editor.fileTooLarge', { maxMb: 5 }))}
        />

        {footer}
      </div>

      <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>{cards}</div>
    </div>
  );
}
