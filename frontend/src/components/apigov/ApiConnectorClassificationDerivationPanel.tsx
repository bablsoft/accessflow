import { Tag, Tooltip } from 'antd';
import { CheckCircleOutlined, MinusCircleOutlined } from '@ant-design/icons';
import { useQuery } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { DataTable, StatusTag } from '@/components/layout';
import {
  apiConnectorClassificationKeys,
  getApiConnectorDerivationPreview,
} from '@/api/apiConnectorClassifications';
import {
  apiMaskingMatcherTypeLabel,
  dataClassificationLabel,
  maskingStrategyLabel,
} from '@/utils/enumLabels';
import type {
  ApiConnectorClassificationMaskingSuggestion,
  ApiMaskingMatcherType,
  DataClassification,
  MaskingStrategy,
} from '@/types/api';

/**
 * Read-only summary of the stricter handling a connector's classification tags imply: the aggregated
 * review posture (suggested, never auto-applied to the shared review plan) and the per-field masking
 * suggestions with an applied / not-applied indicator.
 */
export function ApiConnectorClassificationDerivationPanel({ connectorId }: { connectorId: string }) {
  const { t } = useTranslation();

  const derivationQuery = useQuery({
    queryKey: apiConnectorClassificationKeys.derivation(connectorId),
    queryFn: () => getApiConnectorDerivationPreview(connectorId),
  });

  const derivation = derivationQuery.data;
  const drivenBy = derivation?.suggested_review_posture.driven_by ?? [];
  if (!derivationQuery.isLoading && drivenBy.length === 0) {
    return null;
  }

  const posture = derivation?.suggested_review_posture;

  return (
    <div className="u-mt-6 u-bg-sunken u-rounded-md" style={{ padding: '14px 16px' }}>
      <div className="u-fw-600 u-mb-1">
        {t('apiGov.settings.classification.derivation_title')}
      </div>
      <div className="muted u-text-xs u-mb-3 u-max-w-text">
        {t('apiGov.settings.classification.derivation_description')}
      </div>

      {posture && (
        <div className="u-mb-4">
          <div className="muted u-text-2xs" style={{ marginBottom: 6 }}>
            {t('apiGov.settings.classification.review_posture_label')}
          </div>
          <div className="u-flex u-wrap u-gap-2 u-items-center">
            <PostureFlag on={posture.requires_ai_review} label={t('apiGov.settings.classification.posture_ai_review')} />
            <PostureFlag on={posture.requires_human_approval} label={t('apiGov.settings.classification.posture_human_approval')} />
            <Tag>
              {t('apiGov.settings.classification.posture_min_approvals', {
                count: posture.min_approvals,
              })}
            </Tag>
            <span className="muted u-text-2xs">
              {t('apiGov.settings.classification.driven_by')}:
            </span>
            {drivenBy.map((c: DataClassification) => (
              <StatusTag tone="danger" key={c}>
                {dataClassificationLabel(t, c)}
              </StatusTag>
            ))}
          </div>
        </div>
      )}

      <div className="muted u-text-2xs" style={{ marginBottom: 6 }}>
        {t('apiGov.settings.classification.masking_suggestions_label')}
      </div>
      <DataTable<ApiConnectorClassificationMaskingSuggestion>
        rowKey={(r) => `${r.matcher_type}:${r.operation_id ?? ''}:${r.field_ref}:${r.classification}`}
        size="small"
        loading={derivationQuery.isLoading}
        dataSource={derivation?.masking_suggestions ?? []}
        pagination={false}
        emptyTitle={t('apiGov.settings.classification.no_masking_suggestions')}
        columns={[
          {
            title: t('apiGov.settings.classification.col_field'),
            render: (_v, r) => (
              <span className="mono u-text-xs">
                {r.operation_id ? `${r.operation_id} · ` : ''}
                {r.field_ref}
              </span>
            ),
          },
          {
            title: t('apiGov.settings.classification.col_matcher'),
            dataIndex: 'matcher_type',
            width: 130,
            render: (v: ApiMaskingMatcherType) => <Tag>{apiMaskingMatcherTypeLabel(t, v)}</Tag>,
          },
          {
            title: t('apiGov.settings.classification.col_classification'),
            dataIndex: 'classification',
            width: 120,
            render: (v: DataClassification) => <Tag>{dataClassificationLabel(t, v)}</Tag>,
          },
          {
            title: t('apiGov.settings.classification.col_strategy'),
            dataIndex: 'suggested_strategy',
            width: 150,
            render: (v: MaskingStrategy) => <Tag>{maskingStrategyLabel(t, v)}</Tag>,
          },
          {
            title: t('apiGov.settings.classification.col_applied'),
            dataIndex: 'already_applied',
            width: 90,
            align: 'center',
            render: (applied: boolean) => {
              const label = applied
                ? t('apiGov.settings.classification.applied')
                : t('apiGov.settings.classification.not_applied');
              return (
                <Tooltip title={label}>
                  {applied ? (
                    <CheckCircleOutlined aria-label={label} className="u-fg-success" />
                  ) : (
                    <MinusCircleOutlined aria-label={label} className="muted" />
                  )}
                </Tooltip>
              );
            },
          },
        ]}
      />
    </div>
  );
}

function PostureFlag({ on, label }: { on: boolean; label: string }) {
  return (
    <Tag icon={on ? <CheckCircleOutlined /> : <MinusCircleOutlined />} color={on ? 'green' : 'default'}>
      {label}
    </Tag>
  );
}
