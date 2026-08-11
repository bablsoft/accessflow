import { describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import dayjs from 'dayjs';
import '@/i18n';

const { RecurrencePicker, cronLooksValid } = await import('./RecurrencePicker');

describe('cronLooksValid', () => {
  it('accepts a 6-field expression and rejects everything else', () => {
    expect(cronLooksValid('0 0 8 * * MON')).toBe(true);
    expect(cronLooksValid('0 8 * * MON')).toBe(false);
    expect(cronLooksValid('every monday')).toBe(false);
  });
});

describe('RecurrencePicker', () => {
  function renderPicker(overrides: Partial<Parameters<typeof RecurrencePicker>[0]> = {}) {
    const props = {
      mode: 'none' as const,
      onModeChange: vi.fn(),
      rule: '',
      onRuleChange: vi.fn(),
      until: null,
      onUntilChange: vi.fn(),
      ...overrides,
    };
    render(<RecurrencePicker {...props} />);
    return props;
  }

  it('renders only the mode select when mode is none', () => {
    renderPicker();
    expect(screen.getByTestId('recurrence-mode')).toBeInTheDocument();
    expect(screen.queryByTestId('recurrence-interval')).toBeNull();
    expect(screen.queryByTestId('recurrence-cron')).toBeNull();
    expect(screen.queryByTestId('recurrence-until')).toBeNull();
  });

  it('shows the interval preset select and the series end picker in interval mode', () => {
    renderPicker({ mode: 'interval' });
    expect(screen.getByTestId('recurrence-interval')).toBeInTheDocument();
    expect(screen.getByTestId('recurrence-until')).toBeInTheDocument();
    // With no preset chosen yet, the rule-required hint explains the disabled Submit.
    expect(screen.getByText('A recurrence rule is required.')).toBeInTheDocument();
  });

  it('asks for the end time once a rule is chosen but no end is set', () => {
    renderPicker({ mode: 'interval', rule: 'PT6H' });
    expect(screen.getByText('A recurring query needs an end time.')).toBeInTheDocument();
  });

  it('shows the cron input with a UTC hint in cron mode', () => {
    renderPicker({
      mode: 'cron',
      rule: '0 0 8 * * MON',
      until: dayjs().add(7, 'day'),
    });
    expect(screen.getByTestId('recurrence-cron')).toHaveValue('0 0 8 * * MON');
    expect(screen.getByText(/6-field Spring cron, evaluated in UTC/)).toBeInTheDocument();
  });

  it('flags a malformed cron expression', () => {
    renderPicker({ mode: 'cron', rule: 'not a cron', until: dayjs().add(1, 'day') });
    expect(
      screen.getByText('Cron must have 6 space-separated fields.'),
    ).toBeInTheDocument();
  });

  it('flags a series end in the past', () => {
    renderPicker({ mode: 'interval', rule: 'PT6H', until: dayjs().subtract(1, 'minute') });
    expect(screen.getByText('The end time must be in the future.')).toBeInTheDocument();
  });

  it('propagates a rule change from the cron input', () => {
    const props = renderPicker({ mode: 'cron', until: dayjs().add(1, 'day') });
    fireEvent.change(screen.getByTestId('recurrence-cron'), {
      target: { value: '0 0 8 * * MON' },
    });
    expect(props.onRuleChange).toHaveBeenCalledWith('0 0 8 * * MON');
  });

  it('clears the rule and end time when switching back to none', async () => {
    const props = renderPicker({ mode: 'interval', rule: 'PT6H', until: dayjs().add(1, 'day') });
    fireEvent.mouseDown(screen.getByRole('combobox', { name: 'Recurrence mode' }));
    fireEvent.click(await screen.findByText('Does not repeat'));
    expect(props.onModeChange).toHaveBeenCalledWith('none');
    expect(props.onRuleChange).toHaveBeenCalledWith('');
    expect(props.onUntilChange).toHaveBeenCalledWith(null);
  });
});
