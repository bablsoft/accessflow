import { describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { Button, Form } from 'antd';
import '@/i18n';
import { ConditionTreeEditor, type ConditionOperandSpec } from './ConditionTreeEditor';

type Op = 'tags' | 'flag' | 'num' | 'bytes' | 'window' | 'regex' | 'multi';

const operands: ConditionOperandSpec<Op>[] = [
  {
    value: 'tags',
    label: 'Tags operand',
    editor: {
      kind: 'tags',
      field: 'items',
      separators: [','],
      placeholder: 'tags here',
      validate: (values) => (values.includes('bad') ? 'bad entry' : null),
    },
    defaultRow: () => ({ operand: 'tags', negate: false, items: [] }),
  },
  {
    value: 'flag',
    label: 'Flag operand',
    editor: { kind: 'bool', field: 'bool_value', label: 'Flag on' },
    defaultRow: () => ({ operand: 'flag', negate: false, bool_value: true }),
  },
  {
    value: 'num',
    label: 'Number operand',
    editor: {
      kind: 'comparison',
      operatorField: 'op',
      valueField: 'n',
      input: 'number',
      min: 0,
      max: 10,
      ariaLabel: 'Number value',
    },
    defaultRow: () => ({ operand: 'num', negate: false, op: 'GT', n: 1 }),
  },
  {
    value: 'bytes',
    label: 'Bytes operand',
    editor: {
      kind: 'comparison',
      operatorField: 'op',
      valueField: 'b',
      input: 'bytes',
      ariaLabel: 'Bytes value',
    },
    defaultRow: () => ({ operand: 'bytes', negate: false, op: 'GT', b: 1e9 }),
  },
  {
    value: 'window',
    label: 'Window operand',
    editor: { kind: 'time-range', field: 'range' },
    defaultRow: () => ({ operand: 'window', negate: false }),
  },
  {
    value: 'regex',
    label: 'Regex operand',
    editor: {
      kind: 'regex',
      patternField: 'pattern',
      ignoreCaseField: 'ignore_case',
      maxLength: 10,
      patternLabel: 'Pattern field',
      ignoreCaseLabel: 'Ignore case field',
      validate: (p) => (p === 'nope' ? 'regex refused' : null),
    },
    defaultRow: () => ({ operand: 'regex', negate: false, pattern: '', ignore_case: true }),
  },
  {
    value: 'multi',
    label: 'Multi operand',
    editor: {
      kind: 'multi',
      field: 'picks',
      ariaLabel: 'Picks',
      options: [{ value: 'A', label: 'Option A' }],
    },
    defaultRow: () => ({ operand: 'multi', negate: false, picks: ['A'] }),
  },
];

function Harness({
  initial,
  onFinish,
  maxRows,
}: {
  initial: Record<string, unknown>[];
  onFinish: (values: unknown) => void;
  maxRows?: number;
}) {
  const [form] = Form.useForm();
  return (
    <Form
      form={form}
      name="harness"
      initialValues={{ match_type: 'ALL', conditions: initial }}
      onFinish={onFinish}
    >
      <ConditionTreeEditor operands={operands} defaultOperand="flag" maxRows={maxRows} />
      <Button htmlType="submit">submit</Button>
    </Form>
  );
}

const submit = () => fireEvent.click(screen.getByText('submit'));

describe('ConditionTreeEditor', () => {
  it('renders every value-editor kind for the stored rows', () => {
    render(
      <Harness
        onFinish={vi.fn()}
        initial={[
          { operand: 'tags', negate: false, items: ['x'] },
          { operand: 'flag', negate: true, bool_value: true },
          { operand: 'num', negate: false, op: 'GT', n: 3 },
          { operand: 'bytes', negate: false, op: 'GT', b: 1e9 },
          { operand: 'window', negate: false },
          { operand: 'regex', negate: false, pattern: 'a+', ignore_case: true },
          { operand: 'multi', negate: false, picks: ['A'] },
        ]}
      />,
    );
    expect(screen.getByText('Match all conditions')).toBeInTheDocument();
    expect(screen.getByText('Flag on')).toBeInTheDocument();
    expect(screen.getByLabelText('Number value')).toHaveValue('3');
    expect(screen.getByLabelText('Bytes value')).toBeInTheDocument();
    expect(screen.getByLabelText('Pattern field')).toHaveValue('a+');
    expect(screen.getByText('Ignore case field')).toBeInTheDocument();
    expect(screen.getByText('Option A')).toBeInTheDocument();
    expect(document.querySelectorAll('.ant-picker-range')).toHaveLength(1);
    expect(screen.getAllByText('NOT').length).toBeGreaterThan(0);
  });

  it('adds a row seeded from the default operand and removes rows', async () => {
    const onFinish = vi.fn();
    render(<Harness onFinish={onFinish} initial={[{ operand: 'num', negate: false, op: 'GT', n: 2 }]} />);
    fireEvent.click(screen.getByText('Add condition'));
    expect(screen.getByText('Flag on')).toBeInTheDocument();
    fireEvent.click(screen.getAllByLabelText('Remove condition')[0]!);
    submit();
    await waitFor(() => expect(onFinish).toHaveBeenCalled());
    expect(onFinish.mock.calls[0]?.[0]).toMatchObject({
      match_type: 'ALL',
      conditions: [{ operand: 'flag', negate: false, bool_value: true }],
    });
  });

  it('resets a row to the new operand default when the operand changes', async () => {
    const onFinish = vi.fn();
    render(<Harness onFinish={onFinish} initial={[{ operand: 'flag', negate: false, bool_value: false }]} />);
    // Combobox 0 is the match-type select; 1 is the row's operand select.
    fireEvent.mouseDown(screen.getAllByRole('combobox')[1]!);
    await waitFor(() => expect(screen.getByText('Multi operand')).toBeInTheDocument());
    fireEvent.click(screen.getByText('Multi operand'));
    await waitFor(() => expect(screen.getByText('Option A')).toBeInTheDocument());
    submit();
    await waitFor(() => expect(onFinish).toHaveBeenCalled());
    expect(onFinish.mock.calls[0]?.[0]).toMatchObject({
      conditions: [{ operand: 'multi', negate: false, picks: ['A'] }],
    });
  });

  it('requires at least one condition and enforces maxRows', async () => {
    const onFinish = vi.fn();
    const { unmount } = render(<Harness onFinish={onFinish} initial={[]} />);
    submit();
    expect(await screen.findByText('Add at least one condition.')).toBeInTheDocument();
    unmount();
    render(
      <Harness
        onFinish={onFinish}
        maxRows={1}
        initial={[
          { operand: 'flag', negate: false, bool_value: true },
          { operand: 'flag', negate: false, bool_value: false },
        ]}
      />,
    );
    submit();
    expect(await screen.findByText('Use at most 1 conditions.')).toBeInTheDocument();
    expect(onFinish).not.toHaveBeenCalled();
  });

  it('surfaces the tags and regex validators', async () => {
    const onFinish = vi.fn();
    render(
      <Harness
        onFinish={onFinish}
        initial={[
          { operand: 'tags', negate: false, items: ['bad'] },
          { operand: 'regex', negate: false, pattern: 'nope', ignore_case: false },
        ]}
      />,
    );
    submit();
    expect(await screen.findByText('bad entry')).toBeInTheDocument();
    expect(await screen.findByText('regex refused')).toBeInTheDocument();
    expect(onFinish).not.toHaveBeenCalled();
  });

  it('renders no value editor for an operand outside the whitelist and still submits', async () => {
    const onFinish = vi.fn();
    render(
      <Harness
        onFinish={onFinish}
        initial={[
          { operand: 'retired', negate: false },
          { operand: 'bytes', negate: false, op: 'GT', b: 5 },
        ]}
      />,
    );
    expect(screen.queryByText('Flag on')).not.toBeInTheDocument();
    submit();
    await waitFor(() => expect(onFinish).toHaveBeenCalled());
    expect(onFinish.mock.calls[0]?.[0]).toMatchObject({
      conditions: [{ operand: 'retired' }, { operand: 'bytes', b: 5 }],
    });
  });

  it('requires a regex pattern', async () => {
    const onFinish = vi.fn();
    render(
      <Harness onFinish={onFinish} initial={[{ operand: 'regex', negate: false, pattern: '', ignore_case: false }]} />,
    );
    submit();
    await waitFor(() =>
      expect(document.querySelectorAll('.ant-form-item-explain-error').length).toBeGreaterThan(0),
    );
    expect(onFinish).not.toHaveBeenCalled();
  });
});
