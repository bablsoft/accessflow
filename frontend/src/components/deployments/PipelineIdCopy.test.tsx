import { afterEach, describe, expect, it, vi } from 'vitest';
import { act, fireEvent, render, screen } from '@testing-library/react';
import '@/i18n';
import { PipelineIdCopy } from './PipelineIdCopy';

const ID = '11111111-2222-3333-4444-555555555555';

describe('PipelineIdCopy', () => {
  afterEach(() => vi.unstubAllGlobals());

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
    // The rendered text is not the value, so the whole id has to stay reachable on hover.
    expect(screen.getByTestId('pipeline-id')).toHaveAttribute('title', ID);
  });

  it('stops a click on the id text from reaching a clickable row wrapper', async () => {
    const onRowClick = vi.fn();
    render(
      <div onClick={onRowClick}>
        <PipelineIdCopy id={ID} />
      </div>,
    );

    // The id text, not the copy button: AntD stops the button's click on its own, so only a
    // click on the value itself exercises this component's handler.
    await act(async () => {
      fireEvent.click(screen.getByTestId('pipeline-id'));
    });
    expect(onRowClick).not.toHaveBeenCalled();
  });
});
