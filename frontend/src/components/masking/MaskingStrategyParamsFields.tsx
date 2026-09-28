import { Form, Input, InputNumber, Radio, Select, Typography } from 'antd';
import { useTranslation } from 'react-i18next';
import type { MaskingDatePrecision, MaskingStrategy } from '@/types/api';
import {
  MASKING_DATE_PRECISIONS,
  enumOptions,
  maskingDatePrecisionLabel,
} from '@/utils/enumLabels';
import {
  MAX_BOUNDARIES,
  MAX_PATTERN_LENGTH,
  MAX_REPLACEMENT_LENGTH,
  MAX_VISIBLE_LENGTH,
  isValidBoundaries,
  type BucketMode,
} from '@/utils/maskingStrategyParams';

interface MaskingStrategyParamsFieldsProps {
  strategy: MaskingStrategy | undefined;
}

/**
 * The strategy-specific parameter inputs of a masking-policy form (datasource and API-connector
 * masking share it). Rules mirror the backend `MaskingStrategyParamsValidator`; the server stays
 * authoritative, including for regex syntax the browser engine cannot judge.
 */
export function MaskingStrategyParamsFields({ strategy }: MaskingStrategyParamsFieldsProps) {
  const { t } = useTranslation();
  const form = Form.useFormInstance();
  const bucketMode = Form.useWatch<BucketMode | undefined>('bucket_mode', form);

  switch (strategy) {
    case 'PARTIAL':
    case 'KEEP_FIRST': {
      const suffix = strategy === 'PARTIAL';
      return (
        <Form.Item
          name={suffix ? 'visible_suffix' : 'visible_prefix'}
          label={t(suffix ? 'maskingParams.label_visible_suffix' : 'maskingParams.label_visible_prefix')}
          rules={[
            {
              type: 'number',
              min: 1,
              max: MAX_VISIBLE_LENGTH,
              message: t('maskingParams.visible_length_range', { max: MAX_VISIBLE_LENGTH }),
            },
          ]}
        >
          <InputNumber min={1} max={MAX_VISIBLE_LENGTH} style={{ width: 160 }} />
        </Form.Item>
      );
    }
    case 'CONSTANT':
      return (
        <Form.Item
          name="replacement"
          label={t('maskingParams.label_constant_replacement')}
          rules={[
            { required: true, message: t('maskingParams.constant_replacement_required') },
            {
              max: MAX_REPLACEMENT_LENGTH,
              message: t('maskingParams.replacement_max', { max: MAX_REPLACEMENT_LENGTH }),
            },
          ]}
        >
          <Input placeholder={t('maskingParams.placeholder_constant_replacement')} />
        </Form.Item>
      );
    case 'REGEX_REPLACE':
      return (
        <>
          <Form.Item
            name="pattern"
            label={t('maskingParams.label_pattern')}
            rules={[
              { required: true, message: t('maskingParams.pattern_required') },
              {
                max: MAX_PATTERN_LENGTH,
                message: t('maskingParams.pattern_max', { max: MAX_PATTERN_LENGTH }),
              },
            ]}
          >
            <Input className="mono" placeholder="^(\d{4})\d+$" />
          </Form.Item>
          <Form.Item
            name="replacement"
            label={t('maskingParams.label_regex_replacement')}
            extra={t('maskingParams.regex_help')}
            rules={[
              {
                max: MAX_REPLACEMENT_LENGTH,
                message: t('maskingParams.replacement_max', { max: MAX_REPLACEMENT_LENGTH }),
              },
            ]}
          >
            <Input className="mono" placeholder="$1******" />
          </Form.Item>
        </>
      );
    case 'NUMERIC_BUCKET':
      return (
        <>
          <Form.Item
            name="bucket_mode"
            label={t('maskingParams.label_bucket_mode')}
            initialValue="SIZE"
            extra={t('maskingParams.numeric_help')}
          >
            <Radio.Group
              options={[
                { value: 'SIZE', label: t('maskingParams.bucket_mode_size') },
                { value: 'BOUNDARIES', label: t('maskingParams.bucket_mode_boundaries') },
              ]}
            />
          </Form.Item>
          {bucketMode === 'BOUNDARIES' ? (
            <Form.Item
              name="boundaries"
              label={t('maskingParams.label_boundaries')}
              rules={[
                {
                  validator: (_, value: string | undefined) =>
                    isValidBoundaries(value)
                      ? Promise.resolve()
                      : Promise.reject(
                          new Error(t('maskingParams.boundaries_invalid', { max: MAX_BOUNDARIES })),
                        ),
                },
              ]}
            >
              <Input placeholder="18, 30, 65" />
            </Form.Item>
          ) : (
            <Form.Item
              name="bucket_size"
              label={t('maskingParams.label_bucket_size')}
              rules={[
                {
                  validator: (_, value: number | null | undefined) =>
                    value != null && value > 0
                      ? Promise.resolve()
                      : Promise.reject(new Error(t('maskingParams.bucket_size_invalid'))),
                },
              ]}
            >
              <InputNumber min={0} style={{ width: 200 }} placeholder="10000" />
            </Form.Item>
          )}
        </>
      );
    case 'DATE_GENERALIZE':
      return (
        <Form.Item
          name="precision"
          label={t('maskingParams.label_precision')}
          initialValue="YEAR"
          extra={t('maskingParams.date_help')}
          rules={[{ required: true, message: t('maskingParams.precision_required') }]}
        >
          <Select<MaskingDatePrecision>
            style={{ width: 200 }}
            options={enumOptions(MASKING_DATE_PRECISIONS, maskingDatePrecisionLabel, t)}
          />
        </Form.Item>
      );
    case 'NULLIFY':
      return (
        <Typography.Paragraph type="secondary" style={{ fontSize: 12 }}>
          {t('maskingParams.nullify_help')}
        </Typography.Paragraph>
      );
    default:
      return null;
  }
}
