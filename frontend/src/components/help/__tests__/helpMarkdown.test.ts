import { describe, expect, it } from 'vitest';
import { parseHelpMarkdown, parseInline, type HelpBlock } from '../helpMarkdown';

function textOf(blocks: HelpBlock[]): string {
  return blocks
    .map((block) => {
      switch (block.type) {
        case 'code':
          return block.value;
        case 'list':
          return block.items.map(textOf).join('\n');
        case 'blockquote':
          return textOf(block.children);
        default:
          return inlineText(block.children);
      }
    })
    .join('\n');
}

function inlineText(nodes: ReturnType<typeof parseInline>): string {
  return nodes
    .map((node) => {
      switch (node.type) {
        case 'text':
        case 'code':
          return node.value;
        case 'break':
          return '\n';
        default:
          return inlineText(node.children);
      }
    })
    .join('');
}

describe('parseHelpMarkdown', () => {
  it('returns nothing for empty input', () => {
    expect(parseHelpMarkdown('')).toEqual([]);
    expect(parseHelpMarkdown('   \n\n  ')).toEqual([]);
  });

  it('parses headings, shifting nothing and keeping the level', () => {
    expect(parseHelpMarkdown('### Review plans ###')).toEqual([
      { type: 'heading', level: 3, children: [{ type: 'text', value: 'Review plans' }] },
    ]);
  });

  it('keeps a soft line break inside a paragraph as an explicit break', () => {
    const blocks = parseHelpMarkdown('one\ntwo');
    expect(blocks).toHaveLength(1);
    expect(textOf(blocks)).toBe('one\ntwo');
  });

  it('parses unordered and ordered lists, remembering the ordered start', () => {
    const blocks = parseHelpMarkdown('- a\n- b\n\n3. c\n4. d');
    expect(blocks[0]).toMatchObject({ type: 'list', ordered: false, start: 1 });
    expect(blocks[1]).toMatchObject({ type: 'list', ordered: true, start: 3 });
    expect(textOf(blocks)).toBe('a\nb\nc\nd');
  });

  it('nests an indented list inside its parent item', () => {
    const blocks = parseHelpMarkdown('- outer\n  - inner\n- sibling');
    const list = blocks[0];
    expect(list?.type).toBe('list');
    if (list?.type !== 'list') return;
    expect(list.items).toHaveLength(2);
    expect(list.items[0]?.[1]).toMatchObject({ type: 'list', ordered: false });
  });

  it('keeps a loose list (blank lines between items) as one list', () => {
    const blocks = parseHelpMarkdown('- a\n\n- b');
    expect(blocks).toHaveLength(1);
    expect(blocks[0]).toMatchObject({ type: 'list' });
  });

  it('parses a fenced code block verbatim, without inline parsing', () => {
    const blocks = parseHelpMarkdown('```bash\nexport A=**b**\n```\nafter');
    expect(blocks[0]).toEqual({ type: 'code', value: 'export A=**b**' });
    expect(blocks[1]).toMatchObject({ type: 'paragraph' });
  });

  it('closes an unterminated fence at the end of the answer', () => {
    expect(parseHelpMarkdown('~~~\nstill code')).toEqual([{ type: 'code', value: 'still code' }]);
  });

  it('parses a blockquote and its inner blocks', () => {
    const blocks = parseHelpMarkdown('> quoted\n> - item');
    expect(blocks[0]?.type).toBe('blockquote');
    expect(textOf(blocks)).toBe('quoted\nitem');
  });

  it('ends a blockquote at the first unquoted line', () => {
    const blocks = parseHelpMarkdown('> quoted\nafter');
    expect(blocks[0]?.type).toBe('blockquote');
    expect(blocks[1]).toMatchObject({ type: 'paragraph' });
  });

  it('ends a list at a dedented line and at a trailing blank run', () => {
    expect(parseHelpMarkdown('- a\n  continued\nplain')).toMatchObject([
      { type: 'list' },
      { type: 'paragraph' },
    ]);
    expect(parseHelpMarkdown('- a\n\n\nplain')).toMatchObject([
      { type: 'list' },
      { type: 'paragraph' },
    ]);
  });

  it('keeps a second paragraph inside a list item', () => {
    const list = parseHelpMarkdown('- a\n\n  more\n- b')[0];
    expect(list?.type).toBe('list');
    if (list?.type !== 'list') return;
    expect(list.items[0]).toHaveLength(2);
    expect(list.items).toHaveLength(2);
  });

  it('drops a link-reference definition whose target is a URI', () => {
    expect(textOf(parseHelpMarkdown('See the docs [1]\n\n[1]: https://evil.example.com')))
      .toBe('See the docs [1]');
    expect(textOf(parseHelpMarkdown('[1]: //evil.example.com'))).toBe('');
  });

  it('keeps a bracketed legend line whose target is not a URI', () => {
    // Dropping the line is about not printing a model-authored URL; a legend carries no risk.
    expect(textOf(parseHelpMarkdown('Sources:\n\n[1]: Review plans'))).toBe(
      'Sources:\n[1]: Review plans',
    );
  });

  it('degrades past the nesting cap instead of overflowing the stack', () => {
    // The answer is model-authored, so the depth is adversary-controlled; there is no error
    // boundary in the app, and a throw here would blank the whole SPA on every reopen.
    const quotes = parseHelpMarkdown(`${'> '.repeat(20_000)}deep`);
    expect(textOf(quotes)).toContain('deep');
    const items = parseHelpMarkdown(
      Array.from({ length: 2_000 }, (_, n) => `${' '.repeat(n * 2)}- x`).join('\n'),
    );
    expect(items[0]?.type).toBe('list');
    expect(() => parseHelpMarkdown('*'.repeat(40_000))).not.toThrow();
    expect(() => parseHelpMarkdown(`${'*'.repeat(20_000)}a${'*'.repeat(20_000)}`)).not.toThrow();
  });

  it('parses an adversarial run of unmatched brackets in linear time', () => {
    const start = Date.now();
    expect(() => parseHelpMarkdown('[a'.repeat(20_000))).not.toThrow();
    expect(Date.now() - start).toBeLessThan(1_000);
  });
});

describe('parseInline', () => {
  it('parses bold, italic and inline code', () => {
    expect(parseInline('a **b** _c_ `d`')).toEqual([
      { type: 'text', value: 'a ' },
      { type: 'strong', children: [{ type: 'text', value: 'b' }] },
      { type: 'text', value: ' ' },
      { type: 'emphasis', children: [{ type: 'text', value: 'c' }] },
      { type: 'text', value: ' ' },
      { type: 'code', value: 'd' },
    ]);
  });

  it('leaves an underscore inside a word alone, so env var names survive', () => {
    expect(inlineText(parseInline('set max_question_chars and ai_analysis_enabled'))).toBe(
      'set max_question_chars and ai_analysis_enabled',
    );
    expect(parseInline('max_question_chars').every((n) => n.type === 'text')).toBe(true);
  });

  it('does not treat spaced asterisks as emphasis', () => {
    expect(parseInline('2 * 3 * 4')).toEqual([{ type: 'text', value: '2 * 3 * 4' }]);
  });

  it('leaves an unmatched delimiter as literal text', () => {
    expect(inlineText(parseInline('a * b `unclosed'))).toBe('a * b `unclosed');
  });

  it('honours backslash escapes', () => {
    expect(parseInline('\\*not bold\\*')).toEqual([{ type: 'text', value: '*not bold*' }]);
  });

  it('keeps a doubled-backtick code span and strips its padding spaces', () => {
    expect(parseInline('`` a `b` ``')).toEqual([{ type: 'code', value: 'a `b`' }]);
  });

  it('unwraps a markdown link to its label and drops the target', () => {
    expect(parseInline('go [**here**](https://evil.example.com/x?a=(1)) now')).toEqual([
      { type: 'text', value: 'go ' },
      { type: 'strong', children: [{ type: 'text', value: 'here' }] },
      { type: 'text', value: ' now' },
    ]);
  });

  it('drops an image entirely, alt text included', () => {
    expect(parseInline('a ![beacon](https://evil.example.com/b.png) b')).toEqual([
      { type: 'text', value: 'a  b' },
    ]);
  });

  it('does not leave the soft break of a dropped image behind as a blank line', () => {
    expect(parseInline('text\n![beacon](https://evil.example.com/b.png)')).toEqual([
      { type: 'text', value: 'text' },
    ]);
  });

  it('keeps a bare citation marker as literal text', () => {
    expect(parseInline('done. [1] and [2, 3]')).toEqual([
      { type: 'text', value: 'done. [1] and [2, 3]' },
    ]);
  });

  it('leaves an unterminated or multiline link as literal text', () => {
    expect(inlineText(parseInline('[label](https://x'))).toBe('[label](https://x');
    expect(inlineText(parseInline('[la\nbel](https://x)'))).toBe('[la\nbel](https://x)');
    expect(inlineText(parseInline('[label](https://x\ny)'))).toBe('[label](https://x\ny)');
  });

  it('honours escapes inside a link label and its target', () => {
    expect(parseInline('[a\\]b](https://x\\)y)')).toEqual([{ type: 'text', value: 'a]b' }]);
  });

  it('skips a longer backtick run when looking for a code span closer', () => {
    expect(inlineText(parseInline('`a``b`'))).toBe('a``b');
  });

  it('steps past an escaped or doubled closing delimiter', () => {
    expect(inlineText(parseInline('*a\\*b*'))).toBe('a*b');
    // The run is skipped rather than closing early; the leftover delimiter stays literal.
    expect(inlineText(parseInline('*a**b*'))).toBe('a*b*');
    expect(inlineText(parseInline('_a_b_'))).toBe('a_b');
  });

  it('leaves raw HTML and an autolink as literal text', () => {
    expect(inlineText(parseInline('<a href="https://x">y</a> <https://x>'))).toBe(
      '<a href="https://x">y</a> <https://x>',
    );
  });
});
