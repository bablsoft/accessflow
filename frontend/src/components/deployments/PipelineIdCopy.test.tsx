import { describe, expect, it, vi } from 'vitest';
import { act, fireEvent, render, screen } from '@testing-library/react';
import '@/i18n';
import { PipelineIdCopy } from './PipelineIdCopy';

const ID = '11111111-2222-3333-4444-555555555555';

describe('PipelineIdCopy', () => {
  it('renders the whole id and a t()-labelled copy control', () => {
    render(<PipelineIdCopy id={ID} />);

    expect(screen.getByTestId('pipeline-id')).toHaveTextContent(ID);
    expect(screen.getByRole('button', { name: 'Copy pipeline ID' })).toBeInTheDocument();
  });

  it('truncates the rendered id but copies the whole one', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined);
    vi.stubGlobal('navigator', { ...navigator, clipboard: { writeText } });

    render(<PipelineIdCopy id={ID} truncate />);
    expect(screen.getByTestId('pipeline-id')).toHaveTextContent('11111111…');

    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'Copy pipeline ID' }));
    });
    expect(writeText).toHaveBeenCalledWith(ID);
    vi.unstubAllGlobals();
  });

  it('stops a copy click from reaching a clickable row wrapper', async () => {
    const onRowClick = vi.fn();
    render(
      <div onClick={onRowClick}>
        <PipelineIdCopy id={ID} />
      </div>,
    );

    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'Copy pipeline ID' }));
    });
    expect(onRowClick).not.toHaveBeenCalled();
  });
});
