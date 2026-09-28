import { describe, expect, it, vi } from 'vitest';
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { Form, type FormInstance } from 'antd';
import type { MaskingStrategy } from '@/types/api';
import type { StrategyParamFormValues } from '@/utils/maskingStrategyParams';
import { MaskingStrategyParamsFields } from '../MaskingStrategyParamsFields';
import '@/i18n';

function renderFields(strategy: MaskingStrategy | undefined, onReady?: (f: FormInstance) => void) {
  function Harness() {
    const [form] = Form.useForm<StrategyParamFormValues>();
    onReady?.(form);
    return (
      <Form form={form} layout="vertical">
        <MaskingStrategyParamsFields strategy={strategy} />
      </Form>
    );
  }
  return render(<Harness />);
}

describe('MaskingStrategyParamsFields', () => {
  it('renders nothing for parameterless strategies', () => {
    const { container } = renderFields('FULL');
    expect(container.querySelector('input')).toBeNull();
    const { container: noStrategy } = renderFields(undefined);
    expect(noStrategy.querySelector('input')).toBeNull();
  });

  it('rejects an out-of-range visible length', async () => {
    let form: FormInstance | undefined;
    renderFields('KEEP_FIRST', (f) => {
      form = f;
    });
    act(() => {
      form?.setFieldsValue({ visible_prefix: 257 });
    });
    await act(async () => {
      await form?.validateFields().catch(() => undefined);
    });
    expect(await screen.findByText('Enter a whole number between 1 and 256')).toBeInTheDocument();
  });

  it('renders the suffix and prefix length inputs', () => {
    renderFields('PARTIAL');
    expect(screen.getByText('Visible suffix length')).toBeInTheDocument();
    renderFields('KEEP_FIRST');
    expect(screen.getByText('Visible prefix length')).toBeInTheDocument();
  });

  it('requires a constant replacement', async () => {
    let form: FormInstance | undefined;
    renderFields('CONSTANT', (f) => {
      form = f;
    });
    expect(screen.getByText('Replacement value')).toBeInTheDocument();
    await act(async () => {
      await form?.validateFields().catch(() => undefined);
    });
    expect(await screen.findByText('Enter the replacement value')).toBeInTheDocument();
  });

  it('renders pattern and replacement for regex with a pattern requirement', async () => {
    let form: FormInstance | undefined;
    renderFields('REGEX_REPLACE', (f) => {
      form = f;
    });
    expect(screen.getByText('Pattern (Java regular expression)')).toBeInTheDocument();
    expect(screen.getByText('Replacement template')).toBeInTheDocument();
    await act(async () => {
      await form?.validateFields().catch(() => undefined);
    });
    expect(await screen.findByText('Enter a pattern')).toBeInTheDocument();
  });

  it('switches numeric bucketing between size and boundaries and validates each', async () => {
    let form: FormInstance | undefined;
    renderFields('NUMERIC_BUCKET', (f) => {
      form = f;
    });
    expect(screen.getByText('Bucket size')).toBeInTheDocument();
    await act(async () => {
      await form?.validateFields().catch(() => undefined);
    });
    expect(await screen.findByText('Enter a positive number')).toBeInTheDocument();

    fireEvent.click(screen.getByText('Explicit boundaries'));
    await waitFor(() => expect(screen.getByText('Boundaries')).toBeInTheDocument());
    act(() => {
      form?.setFieldsValue({ boundaries: '30, 18' });
    });
    await act(async () => {
      await form?.validateFields().catch(() => undefined);
    });
    expect(await screen.findByText(/strictly ascending/)).toBeInTheDocument();

    act(() => {
      form?.setFieldsValue({ boundaries: '18, 30' });
    });
    const ok = vi.fn();
    await act(async () => {
      await form?.validateFields().then(ok, () => undefined);
    });
    expect(ok).toHaveBeenCalled();
  });

  it('accepts a positive bucket size', async () => {
    let form: FormInstance | undefined;
    renderFields('NUMERIC_BUCKET', (f) => {
      form = f;
    });
    act(() => {
      form?.setFieldsValue({ bucket_size: 10000 });
    });
    const ok = vi.fn();
    await act(async () => {
      await form?.validateFields().then(ok, () => undefined);
    });
    expect(ok).toHaveBeenCalled();
  });

  it('renders the date precision select defaulting to year', () => {
    renderFields('DATE_GENERALIZE');
    expect(screen.getByText('Precision')).toBeInTheDocument();
    expect(screen.getByText('Year')).toBeInTheDocument();
  });

  it('explains nullify', () => {
    renderFields('NULLIFY');
    expect(screen.getByText(/returned as NULL/)).toBeInTheDocument();
  });
});
