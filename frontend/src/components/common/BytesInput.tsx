import { useState } from 'react';
import { InputNumber, Select, Space } from 'antd';
import { useTranslation } from 'react-i18next';
import { BYTE_INPUT_UNITS, byteUnitFactor, pickUnit, type ByteInputUnit } from '@/utils/bytesCap';

interface BytesInputProps {
  /** Raw bytes; the form stores and submits bytes, the unit is display-only. */
  value?: number | null;
  onChange?: (value: number | null) => void;
  id?: string;
  disabled?: boolean;
  placeholder?: string;
  'aria-label'?: string;
}

/**
 * A byte count entered as an amount plus a unit (#941). Controlled through `value`/`onChange` so it
 * drops into a `Form.Item` whose validation rules apply to the raw byte value.
 */
export function BytesInput({
  value,
  onChange,
  id,
  disabled,
  placeholder,
  'aria-label': ariaLabel,
}: BytesInputProps) {
  const { t } = useTranslation();
  const [unit, setUnit] = useState<ByteInputUnit>(() => pickUnit(value));
  const factor = byteUnitFactor(unit);
  const amount = value === null || value === undefined ? null : value / factor;

  const emit = (nextAmount: number | null, nextFactor: number) => {
    onChange?.(nextAmount === null ? null : Math.round(nextAmount * nextFactor));
  };

  return (
    <Space.Compact style={{ width: '100%' }}>
      <InputNumber<number>
        id={id}
        min={0}
        step={1}
        disabled={disabled}
        placeholder={placeholder}
        aria-label={ariaLabel}
        value={amount}
        onChange={(next) => emit(next, factor)}
        style={{ width: '100%' }}
      />
      <Select<ByteInputUnit>
        value={unit}
        disabled={disabled}
        aria-label={t('common.bytes_unit')}
        style={{ width: 90 }}
        options={BYTE_INPUT_UNITS.map((u) => ({ value: u.unit, label: u.unit }))}
        onChange={(next) => {
          setUnit(next);
          emit(amount, byteUnitFactor(next));
        }}
      />
    </Space.Compact>
  );
}
