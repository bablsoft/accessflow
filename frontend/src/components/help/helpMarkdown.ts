/**
 * A deliberately tiny markdown parser for the help assistant's answers (AF-919).
 *
 * <p>The subset is the whole point. This module recognises headings, bold, italic, inline code,
 * fenced code blocks, ordered and unordered lists, blockquotes and paragraphs — and **nothing
 * else**. It has no notion of links, images, autolinks, raw HTML or tables, so a jailbroken model
 * cannot express one: the node union below has no case that could carry a URL, which is what keeps
 * epic #899 decision 6 intact through this change. Anchors on the help panel still come only from
 * the server-resolved `citations` array.
 *
 * Two constructs are actively neutralised rather than merely unrecognised, because leaving them as
 * literal text would print a model-authored URL at the reader:
 * - `[label](url)` and `![alt](url)` — the label survives, the target is dropped; an image emits
 *   nothing at all, so it can never become an on-render beacon.
 * - a link-reference definition line (`[1]: https://…`) is dropped whole, so the `[n]` citation
 *   markers the answer deliberately keeps can never be turned into references.
 *
 * A bare `[1]` or `[2, 3]` is not link syntax and survives as literal text, alongside its chip.
 *
 * The answer is model-authored, so every loop here is bounded: nesting past {@link MAX_DEPTH}
 * degrades to literal text rather than recursing (6 KB of `>` would otherwise overflow the stack,
 * and the app has no error boundary to absorb that), and the link scans give up past a sane length
 * rather than rescanning to end-of-string from every unmatched `[`.
 */

export type HelpInline =
  | { type: 'text'; value: string }
  | { type: 'code'; value: string }
  | { type: 'strong'; children: HelpInline[] }
  | { type: 'emphasis'; children: HelpInline[] }
  | { type: 'break' };

export type HelpBlock =
  | { type: 'paragraph'; children: HelpInline[] }
  | { type: 'heading'; level: number; children: HelpInline[] }
  | { type: 'code'; value: string }
  | { type: 'list'; ordered: boolean; start: number; items: HelpBlock[][] }
  | { type: 'blockquote'; children: HelpBlock[] };

const FENCE_RE = /^ {0,3}(`{3,}|~{3,})(.*)$/;
const HEADING_RE = /^ {0,3}(#{1,6})[ \t]+(.*)$/;
const QUOTE_RE = /^ {0,3}> ?(.*)$/;
const ITEM_RE = /^( *)([-*+]|\d{1,9}[.)])[ \t]+(.*)$/;
/**
 * A link-reference definition whose target is a URI. Narrowed to a scheme or a protocol-relative
 * target on purpose: dropping the line is about not printing a model-authored URL, so a legend line
 * like `[1]: Review plans` carries no risk and stays as text rather than vanishing.
 */
const LINK_DEFINITION_RE = /^ {0,3}\[[^\]]+\]:[ \t]*<?(?:[a-zA-Z][a-zA-Z0-9+.-]*:|\/\/)/;
const ESCAPABLE_RE = /[\\`*_{}[\]()#+\-.!>~|]/;
/** Nesting past this degrades to literal text — blockquotes, lists and emphasis all recurse. */
const MAX_DEPTH = 8;
/** A real link label is short; scanning further from every unmatched `[` is quadratic. */
const MAX_LINK_LABEL = 512;
/** Likewise for the target, which is dropped anyway. */
const MAX_LINK_TARGET = 2048;

/** Parses one answer into the closed block union above. Never throws; unknown syntax stays text. */
export function parseHelpMarkdown(source: string): HelpBlock[] {
  if (!source) {
    return [];
  }
  return parseBlocks(source.replace(/\r\n?/g, '\n').split('\n'), 0);
}

/** The text of `value`, with its line breaks kept — what any capped path degrades to. */
function literal(value: string): HelpInline[] {
  const nodes: HelpInline[] = [];
  value.split('\n').forEach((part, index) => {
    if (index > 0) {
      nodes.push({ type: 'break' });
    }
    if (part !== '') {
      nodes.push({ type: 'text', value: part });
    }
  });
  return nodes;
}

function indentOf(line: string): number {
  return line.length - line.trimStart().length;
}

function dedent(line: string, amount: number): string {
  let cut = 0;
  while (cut < amount && line[cut] === ' ') {
    cut += 1;
  }
  return line.slice(cut);
}

/** True when the line opens a block that a running paragraph must stop before. */
function startsBlock(line: string): boolean {
  return (
    line.trim() === ''
    || FENCE_RE.test(line)
    || HEADING_RE.test(line)
    || QUOTE_RE.test(line)
    || ITEM_RE.test(line)
    || LINK_DEFINITION_RE.test(line)
  );
}

function parseBlocks(lines: string[], depth: number): HelpBlock[] {
  if (depth > MAX_DEPTH) {
    return [{ type: 'paragraph', children: literal(lines.join('\n').trim()) }];
  }
  const blocks: HelpBlock[] = [];
  let i = 0;
  while (i < lines.length) {
    const line = lines[i] ?? '';
    if (line.trim() === '' || LINK_DEFINITION_RE.test(line)) {
      i += 1;
      continue;
    }
    const fence = FENCE_RE.exec(line);
    if (fence) {
      const fenced = parseFence(lines, i, fence[1] ?? '```');
      blocks.push(fenced.block);
      i = fenced.next;
      continue;
    }
    const heading = HEADING_RE.exec(line);
    if (heading) {
      const level = (heading[1] ?? '#').length;
      const text = (heading[2] ?? '').replace(/[ \t]+#+[ \t]*$/, '').trim();
      blocks.push({ type: 'heading', level, children: parseInline(text) });
      i += 1;
      continue;
    }
    if (QUOTE_RE.test(line)) {
      const inner: string[] = [];
      while (i < lines.length) {
        const quoted = QUOTE_RE.exec(lines[i] ?? '');
        if (!quoted) {
          break;
        }
        inner.push(quoted[1] ?? '');
        i += 1;
      }
      blocks.push({ type: 'blockquote', children: parseBlocks(inner, depth + 1) });
      continue;
    }
    if (ITEM_RE.test(line)) {
      const list = parseList(lines, i, depth);
      blocks.push(list.block);
      i = list.next;
      continue;
    }
    const paragraph: string[] = [];
    while (i < lines.length && !startsBlock(lines[i] ?? '')) {
      paragraph.push((lines[i] ?? '').trim());
      i += 1;
    }
    blocks.push({ type: 'paragraph', children: parseInline(paragraph.join('\n')) });
  }
  return blocks;
}

/** Everything between the fences is literal — no inline parsing, so a fenced sample stays verbatim. */
function parseFence(lines: string[], from: number, marker: string): { block: HelpBlock; next: number } {
  const char = marker[0] ?? '`';
  const closing = new RegExp(`^ {0,3}\\${char}{${marker.length},}[ \\t]*$`);
  const body: string[] = [];
  let i = from + 1;
  while (i < lines.length && !closing.test(lines[i] ?? '')) {
    body.push(lines[i] ?? '');
    i += 1;
  }
  // An unterminated fence still yields a code block: the alternative is re-parsing the remainder as
  // prose, which would render half an answer's markup as text.
  return { block: { type: 'code', value: body.join('\n') }, next: Math.min(i + 1, lines.length) };
}

function parseList(lines: string[], from: number, depth: number): { block: HelpBlock; next: number } {
  const first = ITEM_RE.exec(lines[from] ?? '');
  const baseIndent = (first?.[1] ?? '').length;
  const marker = first?.[2] ?? '-';
  const ordered = /\d/.test(marker);
  const start = ordered ? Number.parseInt(marker, 10) : 1;
  const items: HelpBlock[][] = [];
  let i = from;
  while (i < lines.length) {
    // A loose list separates its items with a blank line; only skip blanks that a sibling follows.
    let scan = i;
    while (scan < lines.length && (lines[scan] ?? '').trim() === '') {
      scan += 1;
    }
    const match = ITEM_RE.exec(lines[scan] ?? '');
    if (!match || (match[1] ?? '').length !== baseIndent || /\d/.test(match[2] ?? '') !== ordered) {
      break;
    }
    i = scan + 1;
    const contentIndent = baseIndent + (match[2] ?? '').length + 1;
    const itemLines: string[] = [match[3] ?? ''];
    while (i < lines.length) {
      const next = lines[i] ?? '';
      if (next.trim() === '') {
        const after = lines[i + 1] ?? '';
        if (after.trim() === '' || indentOf(after) <= baseIndent) {
          break;
        }
        itemLines.push('');
        i += 1;
        continue;
      }
      if (indentOf(next) <= baseIndent) {
        break;
      }
      itemLines.push(dedent(next, contentIndent));
      i += 1;
    }
    items.push(parseBlocks(itemLines, depth + 1));
  }
  return { block: { type: 'list', ordered, start, items }, next: i };
}

/** Finds `[label](target)` at `start`, returning the label only — the target is never kept. */
function matchLink(text: string, start: number): { label: string; end: number } | null {
  let i = start + 1;
  let label = '';
  const labelLimit = Math.min(text.length, start + 1 + MAX_LINK_LABEL);
  while (i < labelLimit && text[i] !== ']') {
    if (text[i] === '\\' && i + 1 < text.length) {
      label += text[i + 1];
      i += 2;
      continue;
    }
    if (text[i] === '\n') {
      return null;
    }
    label += text[i];
    i += 1;
  }
  if (text[i] !== ']' || text[i + 1] !== '(') {
    return null;
  }
  i += 2;
  let depth = 1;
  const targetLimit = Math.min(text.length, i + MAX_LINK_TARGET);
  while (i < targetLimit && depth > 0) {
    if (text[i] === '\\') {
      i += 2;
      continue;
    }
    if (text[i] === '\n') {
      return null;
    }
    if (text[i] === '(') {
      depth += 1;
    } else if (text[i] === ')') {
      depth -= 1;
    }
    i += 1;
  }
  return depth === 0 ? { label, end: i } : null;
}

function matchCodeSpan(text: string, start: number): { value: string; end: number } | null {
  let run = 0;
  while (text[start + run] === '`') {
    run += 1;
  }
  const fence = '`'.repeat(run);
  let i = start + run;
  while (i < text.length) {
    const found = text.indexOf(fence, i);
    if (found < 0) {
      return null;
    }
    if (text[found + run] === '`') {
      i = found + run;
      while (text[i] === '`') {
        i += 1;
      }
      continue;
    }
    let value = text.slice(start + run, found);
    if (value.length > 2 && value.startsWith(' ') && value.endsWith(' ') && value.trim() !== '') {
      value = value.slice(1, -1);
    }
    return { value, end: found + run };
  }
  return null;
}

function isWordChar(char: string | undefined): boolean {
  return char !== undefined && /[\p{L}\p{N}]/u.test(char);
}

function matchEmphasis(text: string, start: number, depth: number): { node: HelpInline; end: number } | null {
  const char = text[start];
  if (char !== '*' && char !== '_') {
    return null;
  }
  const strong = text[start + 1] === char;
  const delimiter = strong ? char + char : char;
  // `_` inside a word is a snake_case identifier, not emphasis — env var names must survive intact.
  if (char === '_' && isWordChar(text[start - 1])) {
    return null;
  }
  let from = start + delimiter.length;
  while (from < text.length) {
    const at = text.indexOf(delimiter, from);
    if (at < 0 || at === start + delimiter.length) {
      return null;
    }
    if (text[at - 1] === '\\') {
      from = at + delimiter.length;
      continue;
    }
    if (!strong && text[at + 1] === char) {
      from = at + 1;
      continue;
    }
    if (char === '_' && isWordChar(text[at + delimiter.length])) {
      from = at + delimiter.length;
      continue;
    }
    const inner = text.slice(start + delimiter.length, at);
    // `2 * 3 * 4` is arithmetic; CommonMark's flanking rule rejects a run padded with whitespace.
    if (inner.trim() === '' || /^\s/.test(inner) || /\s$/.test(inner)) {
      return null;
    }
    const children = parseInline(inner, depth + 1);
    return {
      node: strong ? { type: 'strong', children } : { type: 'emphasis', children },
      end: at + delimiter.length,
    };
  }
  return null;
}

/** Parses one run of text. Soft line breaks become explicit breaks, preserving the model's shape. */
export function parseInline(text: string, depth = 0): HelpInline[] {
  if (depth > MAX_DEPTH) {
    return literal(text);
  }
  const nodes: HelpInline[] = [];
  let buffer = '';
  const flush = () => {
    if (buffer !== '') {
      nodes.push({ type: 'text', value: buffer });
      buffer = '';
    }
  };
  let i = 0;
  while (i < text.length) {
    const char = text[i] ?? '';
    if (char === '\\' && ESCAPABLE_RE.test(text[i + 1] ?? '')) {
      buffer += text[i + 1];
      i += 2;
      continue;
    }
    if (char === '\n') {
      flush();
      nodes.push({ type: 'break' });
      i += 1;
      continue;
    }
    if (char === '`') {
      const span = matchCodeSpan(text, i);
      if (span) {
        flush();
        nodes.push({ type: 'code', value: span.value });
        i = span.end;
        continue;
      }
    }
    if (char === '!' && text[i + 1] === '[') {
      const image = matchLink(text, i + 1);
      if (image) {
        // An image emits nothing whatsoever: no element, and no alt text that could carry a URL.
        i = image.end;
        continue;
      }
    }
    if (char === '[') {
      const link = matchLink(text, i);
      if (link) {
        flush();
        nodes.push(...parseInline(link.label, depth + 1));
        i = link.end;
        continue;
      }
    }
    if (char === '*' || char === '_') {
      const emphasis = matchEmphasis(text, i, depth);
      if (emphasis) {
        flush();
        nodes.push(emphasis.node);
        i = emphasis.end;
        continue;
      }
    }
    buffer += char;
    i += 1;
  }
  flush();
  // A dropped image leaves its line's soft break behind; an answer must not end on a blank line.
  while (nodes[0]?.type === 'break') {
    nodes.shift();
  }
  while (nodes[nodes.length - 1]?.type === 'break') {
    nodes.pop();
  }
  return nodes;
}
