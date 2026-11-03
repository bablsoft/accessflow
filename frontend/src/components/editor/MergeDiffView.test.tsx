import { describe, expect, it, vi, beforeEach } from 'vitest';
import { render } from '@testing-library/react';

const { mergeViewMock, destroyMock } = vi.hoisted(() => ({
  mergeViewMock: vi.fn(),
  destroyMock: vi.fn(),
}));

vi.mock('@codemirror/merge', () => ({
  MergeView: class {
    constructor(config: unknown) {
      mergeViewMock(config);
    }
    destroy() {
      destroyMock();
    }
  },
}));

import { MergeDiffView } from './MergeDiffView';

type Config = { a: { doc: string; extensions: unknown[] }; b: { doc: string; extensions: unknown[] } };

describe('MergeDiffView', () => {
  beforeEach(() => {
    mergeViewMock.mockReset();
    destroyMock.mockReset();
  });

  it('puts the old doc on the left and the new doc on the right', () => {
    render(<MergeDiffView oldValue='{"a":1}' newValue='{"a":2}' />);
    const config = mergeViewMock.mock.calls[0]?.[0] as Config;
    expect(config.a.doc).toBe('{"a":1}');
    expect(config.b.doc).toBe('{"a":2}');
  });

  it('adds the caller extensions to both panes', () => {
    const marker = { extension: [] };
    render(<MergeDiffView oldValue="a" newValue="b" extensions={[marker]} />);
    const config = mergeViewMock.mock.calls[0]?.[0] as Config;
    expect(config.a.extensions).toContain(marker);
    expect(config.b.extensions).toContain(marker);
  });

  it('uses the default test id unless one is given, and destroys the view on unmount', () => {
    const { getByTestId, unmount, rerender } = render(<MergeDiffView oldValue="a" newValue="b" />);
    expect(getByTestId('merge-diff-view')).toBeInTheDocument();
    rerender(<MergeDiffView oldValue="a" newValue="b" testId="custom" />);
    expect(getByTestId('custom')).toBeInTheDocument();
    unmount();
    expect(destroyMock).toHaveBeenCalled();
  });
});
