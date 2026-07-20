import { useMemo, useState } from 'react';
import { App, Button, DatePicker, Input, Select, Space } from 'antd';
import dayjs, { type Dayjs } from 'dayjs';
import { useNavigate } from 'react-router-dom';
import { useMutation, useQuery } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { PageHeader } from '@/components/common/PageHeader';
import { apiConnectorKeys, listApiConnectors } from '@/api/apiConnectors';
import { submitApiRequest } from '@/api/apiRequests';
import { apiErrorMessage } from '@/utils/apiErrors';
import { showApiError } from '@/utils/showApiError';
import { ApiAuthoringPanel } from '@/components/apigov/ApiAuthoringPanel';
import {
  apiConnectorVariableKeys,
  listApiConnectorVariableSummaries,
} from '@/api/apiConnectorVariables';
import { useApiAuthoring, type ApiAuthoringValue } from '@/components/apigov/useApiAuthoring';
import { compositionToSubmit, newComposition } from '@/utils/apiRequestComposition';

export default function ApiEditorPage() {
  const { t } = useTranslation();
  const { message } = App.useApp();
  const navigate = useNavigate();

  const [connectorId, setConnectorId] = useState<string>();
  const [justification, setJustification] = useState('');
  const [scheduledFor, setScheduledFor] = useState<Dayjs | null>(null);
  const [value, setValue] = useState<ApiAuthoringValue>({
    operationId: null,
    verb: 'GET',
    requestPath: '',
    composition: newComposition(),
  });

  // AF-613: the submitter-safe projection, so the composer can offer per-request overrides.
  const variablesQuery = useQuery({
    queryKey: apiConnectorVariableKeys.summary(connectorId ?? ''),
    queryFn: () => listApiConnectorVariableSummaries(connectorId as string),
    enabled: !!connectorId,
  });

  const connectorsQuery = useQuery({
    queryKey: apiConnectorKeys.list({ size: 100 }),
    queryFn: () => listApiConnectors({ size: 100 }),
  });
  const connector = useMemo(
    () => connectorsQuery.data?.content.find((c) => c.id === connectorId) ?? null,
    [connectorsQuery.data, connectorId],
  );

  const authoring = useApiAuthoring({ connector, value, onChange: setValue });

  const scheduleInPast = !!scheduledFor && !scheduledFor.isAfter(dayjs());

  const submitMutation = useMutation({
    mutationFn: () => {
      const parts = compositionToSubmit(value.composition);
      return submitApiRequest({
        connector_id: connectorId as string,
        operation_id: value.operationId,
        verb: value.verb,
        request_path: value.requestPath,
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

  if (connectorsQuery.isLoading) {
    return (
      <div style={{ padding: 40, textAlign: 'center' }}>
        <div className="muted">{t('apiGov.editor.loading')}</div>
      </div>
    );
  }

  if ((connectorsQuery.data?.content ?? []).length === 0) {
    return (
      <div style={{ padding: 40, textAlign: 'center' }}>
        <div className="muted">{t('apiGov.editor.noConnectors')}</div>
      </div>
    );
  }

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%' }}>
      <PageHeader title={t('apiGov.editor.title')} />
      <ApiAuthoringPanel
        authoring={authoring}
        connector={connector}
        value={value}
        onChange={setValue}
        connectorVariables={variablesQuery.data ?? []}
        header={
          <label>
            <div className="muted" style={{ marginBottom: 4 }}>{t('apiGov.editor.connector')}</div>
            <Select
              style={{ width: '100%' }}
              placeholder={t('apiGov.editor.selectConnector')}
              value={connectorId}
              onChange={(v) => {
                setConnectorId(v);
                setValue((prev) => ({ ...prev, operationId: null }));
              }}
              options={(connectorsQuery.data?.content ?? []).map((c) => ({ value: c.id, label: c.name }))}
            />
          </label>
        }
        footer={
          <>
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
                onClick={authoring.analyze}
                loading={authoring.analyzing}
                disabled={!authoring.canAnalyze}
              >
                {t('apiGov.editor.analyze')}
              </Button>
              <Button
                type="primary"
                onClick={() => submitMutation.mutate()}
                loading={submitMutation.isPending}
                disabled={!connectorId || !value.verb || !value.requestPath || scheduleInPast}
              >
                {t('apiGov.editor.submit')}
              </Button>
            </Space>
          </>
        }
      />
    </div>
  );
}
