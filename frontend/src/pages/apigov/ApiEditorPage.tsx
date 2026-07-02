import { useMemo, useState } from 'react';
import { App, Button, Card, DatePicker, Input, Select, Space, Tag } from 'antd';
import dayjs, { type Dayjs } from 'dayjs';
import { useNavigate } from 'react-router-dom';
import { useMutation, useQuery } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { PageHeader } from '@/components/common/PageHeader';
import { apiConnectorKeys, listApiConnectors, listApiOperations } from '@/api/apiConnectors';
import { analyzeApiCall, generateApiCall, submitApiRequest } from '@/api/apiRequests';
import { riskLevelLabel } from '@/utils/enumLabels';
import { apiErrorMessage } from '@/utils/apiErrors';
import { showApiError } from '@/utils/showApiError';
import { ApiRequestComposer } from '@/components/apigov/ApiRequestComposer';
import {
  compositionToSubmit,
  emptyComposition,
  type ApiRequestComposition,
} from '@/utils/apiRequestComposition';

export default function ApiEditorPage() {
  const { t } = useTranslation();
  const { message } = App.useApp();
  const navigate = useNavigate();

  const [connectorId, setConnectorId] = useState<string>();
  const [operationId, setOperationId] = useState<string>();
  const [verb, setVerb] = useState('GET');
  const [path, setPath] = useState('');
  const [justification, setJustification] = useState('');
  const [prompt, setPrompt] = useState('');
  const [scheduledFor, setScheduledFor] = useState<Dayjs | null>(null);
  const [composition, setComposition] = useState<ApiRequestComposition>(emptyComposition);

  const connectorsQuery = useQuery({
    queryKey: apiConnectorKeys.list({ size: 100 }),
    queryFn: () => listApiConnectors({ size: 100 }),
  });
  const connector = useMemo(
    () => connectorsQuery.data?.content.find((c) => c.id === connectorId),
    [connectorsQuery.data, connectorId],
  );

  const operationsQuery = useQuery({
    queryKey: connectorId ? apiConnectorKeys.operations(connectorId) : ['api-operations', 'none'],
    queryFn: () => listApiOperations(connectorId as string),
    enabled: !!connectorId && !!connector?.schema_present,
  });

  const sortedOperations = useMemo(
    () =>
      [...(operationsQuery.data ?? [])].sort(
        (a, b) => a.verb.localeCompare(b.verb) || a.path.localeCompare(b.path),
      ),
    [operationsQuery.data],
  );

  const scheduleInPast = !!scheduledFor && !scheduledFor.isAfter(dayjs());

  const analyzeMutation = useMutation({
    mutationFn: () =>
      analyzeApiCall({
        connector_id: connectorId as string,
        operation_id: operationId ?? null,
        verb,
        request_path: path,
        request_body: compositionToSubmit(composition).request_body,
      }),
    onError: (err) => showApiError(message, err, (e) => apiErrorMessage(e, () => t('apiGov.error'))),
  });

  const generateMutation = useMutation({
    mutationFn: () => generateApiCall({ connector_id: connectorId as string, prompt }),
    onSuccess: (result) => {
      setComposition((c) => ({ ...c, bodyType: 'RAW', rawBody: result.draft }));
      message.success(t('apiGov.editor.generated'));
    },
    onError: (err) => showApiError(message, err, (e) => apiErrorMessage(e, () => t('apiGov.error'))),
  });

  const submitMutation = useMutation({
    mutationFn: () => {
      const parts = compositionToSubmit(composition);
      return submitApiRequest({
        connector_id: connectorId as string,
        operation_id: operationId ?? null,
        verb,
        request_path: path,
        justification: justification || null,
        scheduled_for: scheduledFor ? scheduledFor.toISOString() : null,
        ...parts,
      });
    },
    onSuccess: (created) => {
      message.success(t('apiGov.editor.submitted'));
      navigate(`/api-requests/${created.id}`);
    },
    onError: (err) => showApiError(message, err, (e) => apiErrorMessage(e, () => t('apiGov.error'))),
  });

  const preview = analyzeMutation.data;
  const canTextToApi = !!connector?.text_to_api_enabled && !!connector?.schema_present;

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%' }}>
      <PageHeader title={t('apiGov.editor.title')} />
      <div style={{ flex: 1, overflow: 'auto', padding: 24, display: 'grid', gridTemplateColumns: '1fr 360px', gap: 20 }}>
        <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
          <label>
            <div className="muted" style={{ marginBottom: 4 }}>{t('apiGov.editor.connector')}</div>
            <Select
              style={{ width: '100%' }}
              placeholder={t('apiGov.editor.selectConnector')}
              value={connectorId}
              onChange={(v) => {
                setConnectorId(v);
                setOperationId(undefined);
              }}
              options={(connectorsQuery.data?.content ?? []).map((c) => ({ value: c.id, label: c.name }))}
            />
          </label>

          {connector?.schema_present && (
            <label>
              <div className="muted" style={{ marginBottom: 4 }}>{t('apiGov.editor.operation')}</div>
              <Select
                style={{ width: '100%' }}
                allowClear
                showSearch
                optionFilterProp="label"
                placeholder={t('apiGov.editor.searchOperation')}
                value={operationId}
                onChange={(v) => {
                  setOperationId(v);
                  const op = sortedOperations.find((o) => o.operation_id === v);
                  if (op) {
                    setVerb(op.verb);
                    setPath(op.path);
                  }
                }}
                options={sortedOperations.map((o) => ({
                  value: o.operation_id,
                  label: `${o.verb} ${o.path}${o.write ? ' (write)' : ''}`,
                }))}
              />
            </label>
          )}

          <Space.Compact style={{ width: '100%' }}>
            <Input
              style={{ width: 120 }}
              value={verb}
              onChange={(e) => setVerb(e.target.value)}
              aria-label={t('apiGov.editor.verb')}
            />
            <Input
              value={path}
              onChange={(e) => setPath(e.target.value)}
              placeholder="/v1/resource"
              aria-label={t('apiGov.editor.path')}
            />
          </Space.Compact>

          <ApiRequestComposer
            value={composition}
            onChange={setComposition}
            defaultHeaders={connector?.default_headers ?? {}}
            onTooLarge={() => message.error(t('apiGov.editor.fileTooLarge', { maxMb: 5 }))}
          />

          <label>
            <div className="muted" style={{ marginBottom: 4 }}>{t('apiGov.editor.scheduleFor')}</div>
            <DatePicker
              showTime
              style={{ width: '100%' }}
              format="YYYY-MM-DD HH:mm"
              value={scheduledFor}
              onChange={setScheduledFor}
            />
            <div className="muted" style={{ marginTop: 4, color: scheduleInPast ? 'var(--af-error)' : undefined }}>
              {scheduleInPast ? t('apiGov.editor.scheduleInPast') : t('apiGov.editor.scheduleHint')}
            </div>
          </label>

          <label>
            <div className="muted" style={{ marginBottom: 4 }}>{t('apiGov.editor.justification')}</div>
            <Input.TextArea rows={2} value={justification} onChange={(e) => setJustification(e.target.value)} />
          </label>

          <Space>
            <Button
              onClick={() => analyzeMutation.mutate()}
              loading={analyzeMutation.isPending}
              disabled={!connectorId}
            >
              {t('apiGov.editor.analyze')}
            </Button>
            <Button
              type="primary"
              onClick={() => submitMutation.mutate()}
              loading={submitMutation.isPending}
              disabled={!connectorId || !verb || !path || scheduleInPast}
            >
              {t('apiGov.editor.submit')}
            </Button>
          </Space>
        </div>

        <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
          <Card size="small" title={t('apiGov.editor.riskPreview')}>
            {preview ? (
              <div>
                <Tag>{riskLevelLabel(t, preview.risk_level)} · {preview.risk_score}</Tag>
                <p style={{ marginTop: 8 }}>{preview.summary}</p>
                <ul style={{ paddingLeft: 18, margin: 0 }}>
                  {preview.issues.map((issue, i) => (
                    <li key={i} style={{ fontSize: 12 }}>{issue}</li>
                  ))}
                </ul>
              </div>
            ) : (
              <span className="muted">{t('apiGov.editor.noPreview')}</span>
            )}
          </Card>

          {canTextToApi && (
            <Card size="small" title={t('apiGov.editor.textToApi')}>
              <Input.TextArea
                rows={3}
                value={prompt}
                onChange={(e) => setPrompt(e.target.value)}
                placeholder={t('apiGov.editor.textToApiHint')}
              />
              <Button
                style={{ marginTop: 8 }}
                onClick={() => generateMutation.mutate()}
                loading={generateMutation.isPending}
                disabled={!prompt}
                block
              >
                {t('apiGov.editor.generate')}
              </Button>
            </Card>
          )}
        </div>
      </div>
    </div>
  );
}
