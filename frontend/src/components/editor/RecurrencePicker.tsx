import { DatePicker, Input, Select } from 'antd';
import dayjs, { type Dayjs } from 'dayjs';
import { useTranslation } from 'react-i18next';

export type RecurrenceMode = 'none' | 'interval' | 'cron';

/** Preset ISO-8601 durations offered in interval mode (#627). All clear the backend's PT5M floor. */
const INTERVAL_PRESETS = ['PT15M', 'PT30M', 'PT1H', 'PT6H', 'PT12H', 'P1D', 'P7D'] as const;

/** Client-side shape check only — the server (`RecurrenceRule.parse`) is the source of truth. */
export function cronLooksValid(rule: string): boolean {
  return rule.trim().split(/\s+/).length === 6;
}

interface RecurrencePickerProps {
  mode: RecurrenceMode;
  onModeChange: (mode: RecurrenceMode) => void;
  rule: string;
  onRuleChange: (rule: string) => void;
  until: Dayjs | null;
  onUntilChange: (until: Dayjs | null) => void;
}

/**
 * Recurrence picker for the query editor (#627): mode select (none / fixed interval / cron),
 * the rule input for the chosen mode, and the mandatory series end time. Cron rules are 6-field
 * Spring expressions evaluated in UTC — the help text says so to avoid operator surprise.
 * Labels are plain `<label>`s to match the editor footer (not an AntD Form), so every control
 * carries an explicit aria-label for tests and screen readers.
 */
export function RecurrencePicker({
  mode,
  onModeChange,
  rule,
  onRuleChange,
  until,
  onUntilChange,
}: RecurrencePickerProps) {
  const { t } = useTranslation();
  const untilInPast = !!until && !until.isAfter(dayjs());
  const cronInvalid = mode === 'cron' && rule.trim().length > 0 && !cronLooksValid(rule);
  const ruleMissing = mode !== 'none' && rule.trim().length === 0;

  const helpText = () => {
    if (mode === 'cron' && cronInvalid) {
      return t('editor.recurrence_cron_invalid');
    }
    if (ruleMissing) {
      return t('editor.recurrence_rule_missing_error');
    }
    if (mode !== 'none' && !until) {
      return t('editor.recurrence_until_missing_error');
    }
    if (untilInPast) {
      return t('editor.recurrence_until_in_past_error');
    }
    if (mode === 'cron') {
      return t('editor.recurrence_cron_help');
    }
    return t('editor.recurrence_help');
  };

  return (
    <div>
      <label
        className="muted"
        style={{ display: 'block', fontSize: 11.5, fontWeight: 500, marginBottom: 5 }}
      >
        {t('editor.recurrence_label')}{' '}
        <span className="muted" style={{ fontWeight: 400 }}>
          {t('editor.recurrence_optional_note')}
        </span>
      </label>
      <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
        <Select<RecurrenceMode>
          value={mode}
          onChange={(next) => {
            onModeChange(next);
            onRuleChange('');
            if (next === 'none') {
              onUntilChange(null);
            }
          }}
          aria-label={t('editor.recurrence_mode_aria')}
          data-testid="recurrence-mode"
          style={{ width: 200 }}
          options={[
            { value: 'none', label: t('editor.recurrence_mode_none') },
            { value: 'interval', label: t('editor.recurrence_mode_interval') },
            { value: 'cron', label: t('editor.recurrence_mode_cron') },
          ]}
        />
        {mode === 'interval' && (
          <Select
            value={rule || undefined}
            onChange={(next) => onRuleChange(next)}
            placeholder={t('editor.recurrence_interval_placeholder')}
            aria-label={t('editor.recurrence_interval_aria')}
            data-testid="recurrence-interval"
            status={ruleMissing ? 'error' : undefined}
            style={{ width: 200 }}
            options={INTERVAL_PRESETS.map((preset) => ({
              value: preset,
              label: t(`editor.recurrence_interval_${preset}`),
            }))}
          />
        )}
        {mode === 'cron' && (
          <Input
            value={rule}
            onChange={(e) => onRuleChange(e.target.value)}
            placeholder={t('editor.recurrence_cron_placeholder')}
            aria-label={t('editor.recurrence_cron_aria')}
            data-testid="recurrence-cron"
            status={cronInvalid || ruleMissing ? 'error' : undefined}
            style={{ width: 200, fontFamily: 'monospace' }}
          />
        )}
        {mode !== 'none' && (
          <DatePicker
            showTime
            value={until}
            onChange={(value) => onUntilChange(value)}
            placeholder={t('editor.recurrence_until_placeholder')}
            aria-label={t('editor.recurrence_until_aria')}
            data-testid="recurrence-until"
            disabledDate={(d) => !!d && d.isBefore(dayjs().startOf('day'))}
            status={untilInPast || !until ? 'error' : undefined}
            style={{ width: 240 }}
          />
        )}
      </div>
      {mode !== 'none' && (
        <div className="muted" style={{ fontSize: 11, marginTop: 4 }}>
          {helpText()}
        </div>
      )}
    </div>
  );
}
