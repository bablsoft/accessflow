import { Table, Tag, Tooltip } from 'antd';
import { CheckCircleOutlined, MinusCircleOutlined } from '@ant-design/icons';
import { useQuery } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
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
    <div style={{ marginTop: 24, padding: '14px 16px', background: 'var(--bg-sunken)', borderRadius: 8 }}>
      <div style={{ fontWeight: 600, marginBottom: 4 }}>
        {t('apiGov.settings.classification.derivation_title')}
      </div>
      <div className="muted" style={{ fontSize: 12, marginBottom: 12, maxWidth: 640 }}>
        {t('apiGov.settings.classification.derivation_description')}
      </div>

      {posture && (
        <div style={{ marginBottom: 16 }}>
          <div className="muted" style={{ fontSize: 11, marginBottom: 6 }}>
            {t('apiGov.settings.classification.review_posture_label')}
          </div>
          <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8, alignItems: 'center' }}>
            <PostureFlag on={posture.requires_ai_review} label={t('apiGov.settings.classification.posture_ai_review')} />
            <PostureFlag on={posture.requires_human_approval} label={t('apiGov.settings.classification.posture_human_approval')} />
            <Tag>
              {t('apiGov.settings.classification.posture_min_approvals', {
                count: posture.min_approvals,
              })}
            </Tag>
            <span className="muted" style={{ fontSize: 11 }}>
              {t('apiGov.settings.classification.driven_by')}:
            </span>
            {drivenBy.map((c: DataClassification) => (
              <Tag key={c} color="volcano">
                {dataClassificationLabel(t, c)}
              </Tag>
            ))}
          </div>
        </div>
      )}

      <div className="muted" style={{ fontSize: 11, marginBottom: 6 }}>
        {t('apiGov.settings.classification.masking_suggestions_label')}
      </div>
      <Table<ApiConnectorClassificationMaskingSuggestion>
        rowKey={(r) => `${r.matcher_type}:${r.operation_id ?? ''}:${r.field_ref}:${r.classification}`}
        size="small"
        loading={derivationQuery.isLoading}
        dataSource={derivation?.masking_suggestions ?? []}
        pagination={false}
        locale={{ emptyText: t('apiGov.settings.classification.no_masking_suggestions') }}
        columns={[
          {
            title: t('apiGov.settings.classification.col_field'),
            render: (_v, r) => (
              <span className="mono" style={{ fontSize: 12 }}>
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
                    <CheckCircleOutlined aria-label={label} style={{ color: 'var(--risk-low)' }} />
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
