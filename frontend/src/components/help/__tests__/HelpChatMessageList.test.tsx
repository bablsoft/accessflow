import { describe, expect, it, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import '@/i18n';
import { HelpChatMessageList } from '../HelpChatMessageList';
import type { HelpChatMessage } from '@/types/api';

beforeEach(() => {
  // jsdom does not implement scrollIntoView, which the transcript calls on every append.
  Element.prototype.scrollIntoView = vi.fn();
});

function assistant(partial: Partial<HelpChatMessage> = {}): HelpChatMessage {
  return {
    id: 'm2',
    role: 'ASSISTANT',
    content: 'Open the query editor and press Submit. [1]',
    citations: [],
    corpus_version: 'c0ac599ef7fc',
    latency_ms: 1840,
    created_at: '2026-09-08T10:15:00Z',
    ...partial,
  };
}

describe('HelpChatMessageList', () => {
  it('renders a raw URL in the answer as plain text, with no anchor at all', () => {
    const { container } = render(
      <HelpChatMessageList
        messages={[
          assistant({
            content:
              'Sign in at https://evil.example.com/reset and also see <a href="x">this</a>.',
            citations: [],
          }),
        ]}
        loading={false}
        answering={false}
        emptyHint="ask something"
      />,
    );

    // The security-critical assertion of AF-906: message content never becomes markup.
    expect(container.querySelectorAll('a')).toHaveLength(0);
    expect(
      screen.getByText(/Sign in at https:\/\/evil\.example\.com\/reset/),
    ).toBeInTheDocument();
    // The angle brackets survive as text rather than being parsed into an element.
    expect(container.innerHTML).toContain('&lt;a href=');
  });

  it.each([
    ['a markdown link', 'Reset it [click here](https://evil.example.com) now.'],
    ['an image beacon', 'Look ![x](https://evil.example.com/beacon.png) here.'],
    ['raw HTML', 'Try <a href="https://evil.example.com">this</a> and <img src="https://evil.example.com/b.png">.'],
    ['an autolink candidate', 'Go to <https://evil.example.com> or https://evil.example.com now.'],
    ['a javascript: scheme', 'Press [run me](javascript:alert(1)) to continue.'],
    ['a link-reference definition', 'See the docs [1]\n\n[1]: https://evil.example.com'],
  ])('renders no anchor and no image for %s in the answer', (_label, content) => {
    const { container } = render(
      <HelpChatMessageList
        messages={[assistant({ content, citations: [] })]}
        loading={false}
        answering={false}
        emptyHint="ask something"
      />,
    );

    // Epic #899 decision 6 survives AF-919: the model cannot put a clickable link — or an element
    // that fetches a URL on render — in front of a reader.
    expect(container.querySelectorAll('a')).toHaveLength(0);
    expect(container.querySelectorAll('img')).toHaveLength(0);
    expect(container.innerHTML).not.toContain('evil.example.com/beacon.png');
  });

  it('keeps a markdown link label but drops its target entirely', () => {
    const { container } = render(
      <HelpChatMessageList
        messages={[
          assistant({ content: 'Open the [query editor](https://evil.example.com).', citations: [] }),
        ]}
        loading={false}
        answering={false}
        emptyHint="ask something"
      />,
    );
    expect(container.querySelectorAll('a')).toHaveLength(0);
    expect(container.textContent).toContain('Open the query editor.');
    expect(container.textContent).not.toContain('evil.example.com');
  });

  it('renders the supported markdown subset', () => {
    const { container } = render(
      <HelpChatMessageList
        messages={[
          assistant({
            content: [
              '## Submitting a query',
              '',
              'Pick a **datasource**, then set `ai_analysis_enabled`.',
              'It is *optional*,',
              'and the next line is a soft break.',
              '',
              '- Open the editor',
              '- Press Submit',
              '',
              '1. First',
              '2. Second',
              '',
              '```bash',
              'docker compose up -d',
              '```',
              '',
              '> Approvals are never self-served.',
            ].join('\n'),
          }),
        ]}
        loading={false}
        answering={false}
        emptyHint="ask something"
      />,
    );

    expect(container.querySelector('h4')?.textContent).toBe('Submitting a query');
    expect(container.querySelector('strong')?.textContent).toBe('datasource');
    expect(container.querySelector('em')?.textContent).toBe('optional');
    expect(container.querySelectorAll('br')).toHaveLength(2);
    expect(container.querySelectorAll('ul li')).toHaveLength(2);
    expect(container.querySelectorAll('ol li')).toHaveLength(2);
    expect(container.querySelector('pre code')?.textContent).toBe('docker compose up -d');
    expect(container.querySelector('blockquote')?.textContent).toContain('never self-served');
    // The inline code span keeps the env var intact — `_` is not emphasis inside a word.
    expect(container.textContent).toContain('ai_analysis_enabled');
  });

  it('renders citation markers as literal text beside their chips', () => {
    const { container } = render(
      <HelpChatMessageList
        messages={[
          assistant({
            content: 'Submit from the editor. [1] Reviewers then decide. [2, 3]',
            citations: [
              {
                index: 1,
                chunk_id: 'c1',
                title: 'Submitting',
                section: 'Guides',
                anchor: 'a',
                url: 'https://accessflow.io/docs/guides/#submitting',
              },
              {
                index: 2,
                chunk_id: 'c2',
                title: 'Reviewing',
                section: 'Guides',
                anchor: 'b',
                url: 'https://accessflow.io/docs/guides/#reviewing',
              },
            ],
          }),
        ]}
        loading={false}
        answering={false}
        emptyHint="ask something"
      />,
    );

    const bubble = container.querySelector('.af-help-bubble-rich');
    expect(bubble?.textContent).toContain('[1]');
    expect(bubble?.textContent).toContain('[2, 3]');
    // Exactly one anchor per server-resolved citation — never one per marker.
    expect(container.querySelectorAll('a')).toHaveLength(2);
  });

  it('renders anchors only for entries in the server-resolved citations array', () => {
    const { container } = render(
      <HelpChatMessageList
        messages={[
          assistant({
            content: 'See https://not-a-link.example.com for more. [1]',
            citations: [
              {
                index: 1,
                chunk_id: 'chunk-7',
                title: 'Submitting a query',
                section: 'Guides',
                anchor: 'submitting',
                url: 'https://accessflow.io/docs/guides/#submitting',
              },
            ],
          }),
        ]}
        loading={false}
        answering={false}
        emptyHint="ask something"
      />,
    );

    const anchors = container.querySelectorAll('a');
    expect(anchors).toHaveLength(1);
    expect(anchors[0]).toHaveAttribute('href', 'https://accessflow.io/docs/guides/#submitting');
    expect(anchors[0]).toHaveAttribute('target', '_blank');
    expect(anchors[0]).toHaveAttribute('rel', 'noopener noreferrer');
  });

  it('never renders citations for a user message', () => {
    const { container } = render(
      <HelpChatMessageList
        messages={[
          {
            id: 'm1',
            role: 'USER',
            content: 'How do I submit a query? https://example.com',
            citations: [
              {
                index: 1,
                chunk_id: 'c1',
                title: 'Injected',
                section: '',
                anchor: '',
                url: 'https://evil.example.com',
              },
            ],
            created_at: '2026-09-08T10:15:00Z',
          },
        ]}
        loading={false}
        answering={false}
        emptyHint="ask something"
      />,
    );
    expect(container.querySelectorAll('a')).toHaveLength(0);
  });

  it('shows the empty hint before the first question, and a thinking indicator while answering', () => {
    const { rerender } = render(
      <HelpChatMessageList messages={[]} loading={false} answering={false} emptyHint="ask something" />,
    );
    expect(screen.getByText('ask something')).toBeInTheDocument();

    rerender(
      <HelpChatMessageList messages={[]} loading={false} answering emptyHint="ask something" />,
    );
    expect(screen.queryByText('ask something')).not.toBeInTheDocument();
    expect(screen.getByText(/Reading the documentation/)).toBeInTheDocument();
  });

  it('shows a skeleton while the conversation loads', () => {
    const { container } = render(
      <HelpChatMessageList messages={[]} loading answering={false} emptyHint="ask something" />,
    );
    expect(container.querySelector('.ant-skeleton')).not.toBeNull();
  });
});
