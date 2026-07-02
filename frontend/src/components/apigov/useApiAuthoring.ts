import { useMemo, useState } from 'react';
import { App } from 'antd';
import { useMutation, useQuery } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { apiConnectorKeys, listApiOperations } from '@/api/apiConnectors';
import { analyzeApiCall, generateApiCall } from '@/api/apiRequests';
import { compositionToSubmit, type ApiRequestComposition } from '@/utils/apiRequestComposition';
import { apiErrorMessage } from '@/utils/apiErrors';
import { showApiError } from '@/utils/showApiError';
import type { ApiAiPreview, ApiConnector, ApiOperation } from '@/types/api';

/** The controlled slice of an API call being authored (host owns it; #559). */
export interface ApiAuthoringValue {
  operationId: string | null;
  verb: string;
  requestPath: string;
  composition: ApiRequestComposition;
}

export interface UseApiAuthoringArgs {
  connector: ApiConnector | null;
  value: ApiAuthoringValue;
  onChange: (next: ApiAuthoringValue) => void;
}

/**
 * Transient API-authoring state shared by the standalone API Editor and the group-builder member
 * drawer (#559): the connector's operation catalog (auto-filling verb/path on pick), the AI risk
 * preview, and the text-to-API draft generator. The host stays in charge of connector selection
 * and submission concerns.
 */
export interface ApiAuthoring {
  operations: ApiOperation[];
  selectOperation: (operationId: string | undefined) => void;
  analyzing: boolean;
  preview: ApiAiPreview | null;
  canAnalyze: boolean;
  analyze: () => void;
  canTextToApi: boolean;
  prompt: string;
  setPrompt: (prompt: string) => void;
  generating: boolean;
  generate: () => void;
}

export function useApiAuthoring({ connector, value, onChange }: UseApiAuthoringArgs): ApiAuthoring {
  const { t } = useTranslation();
  const { message } = App.useApp();
  const [prompt, setPrompt] = useState('');

  const operationsQuery = useQuery({
    queryKey: connector ? apiConnectorKeys.operations(connector.id) : ['api-operations', 'none'],
    queryFn: () => listApiOperations(connector!.id),
    enabled: !!connector?.schema_present,
  });

  const operations = useMemo(
    () =>
      [...(operationsQuery.data ?? [])].sort(
        (a, b) => a.verb.localeCompare(b.verb) || a.path.localeCompare(b.path),
      ),
    [operationsQuery.data],
  );

  const analyzeMutation = useMutation({
    mutationFn: () =>
      analyzeApiCall({
        connector_id: connector!.id,
        operation_id: value.operationId,
        verb: value.verb,
        request_path: value.requestPath,
        request_body: compositionToSubmit(value.composition).request_body,
      }),
    onError: (err) => showApiError(message, err, (e) => apiErrorMessage(e, () => t('apiGov.error'))),
  });

  const generateMutation = useMutation({
    mutationFn: () => generateApiCall({ connector_id: connector!.id, prompt }),
    onSuccess: (result) => {
      onChange({
        ...value,
        composition: { ...value.composition, bodyType: 'RAW', rawBody: result.draft },
      });
      message.success(t('apiGov.editor.generated'));
    },
    onError: (err) => showApiError(message, err, (e) => apiErrorMessage(e, () => t('apiGov.error'))),
  });

  return {
    operations,
    selectOperation: (operationId) => {
      const op = operations.find((o) => o.operation_id === operationId);
      onChange({
        ...value,
        operationId: operationId ?? null,
        ...(op ? { verb: op.verb, requestPath: op.path } : {}),
      });
    },
    analyzing: analyzeMutation.isPending,
    preview: analyzeMutation.data ?? null,
    canAnalyze: !!connector,
    analyze: () => analyzeMutation.mutate(),
    canTextToApi: !!connector?.text_to_api_enabled && !!connector?.schema_present,
    prompt,
    setPrompt,
    generating: generateMutation.isPending,
    generate: () => generateMutation.mutate(),
  };
}
