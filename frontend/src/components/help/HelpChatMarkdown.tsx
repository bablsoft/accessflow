import { Fragment, createElement, useMemo, type ReactNode } from 'react';
import { parseHelpMarkdown, type HelpBlock, type HelpInline } from './helpMarkdown';

/**
 * Renders an assistant answer's safe markdown subset (AF-919).
 *
 * <p>Every element this component can produce is listed in the two switches below, and neither list
 * contains `a` or `img` — there is no code path here that emits an anchor, a `src`, an `href` or a
 * `dangerouslySetInnerHTML`. That is the guarantee, and it does not depend on an allow-list being
 * configured correctly: the parser in {@link parseHelpMarkdown} cannot even represent a URL.
 * The panel's only links remain the server-resolved citation chips (epic #899 decision 6).
 */
/** Answers live in a drawer, so `#` starts at `h3`; the level is already clamped to 1..6. */
const HEADING_TAGS = ['h3', 'h4', 'h5', 'h6', 'h6', 'h6'] as const;

export function HelpChatMarkdown({ content }: { content: string }) {
  const blocks = useMemo(() => parseHelpMarkdown(content), [content]);
  return <div className="af-help-md">{renderBlocks(blocks)}</div>;
}

function renderBlocks(blocks: HelpBlock[]): ReactNode {
  return blocks.map((block, index) => {
    const key = `b${index}`;
    switch (block.type) {
      case 'heading':
        return createElement(
          HEADING_TAGS[block.level - 1] ?? 'h6',
          { key },
          renderInline(block.children),
        );
      case 'code':
        return (
          <pre key={key} className="af-help-md-code">
            <code>{block.value}</code>
          </pre>
        );
      case 'list':
        return block.ordered ? (
          <ol key={key} start={block.start}>
            {block.items.map((item, itemIndex) => (
              <li key={`${key}i${itemIndex}`}>{renderBlocks(item)}</li>
            ))}
          </ol>
        ) : (
          <ul key={key}>
            {block.items.map((item, itemIndex) => (
              <li key={`${key}i${itemIndex}`}>{renderBlocks(item)}</li>
            ))}
          </ul>
        );
      case 'blockquote':
        return <blockquote key={key}>{renderBlocks(block.children)}</blockquote>;
      case 'paragraph':
      default:
        return <p key={key}>{renderInline(block.children)}</p>;
    }
  });
}

function renderInline(nodes: HelpInline[]): ReactNode {
  return nodes.map((node, index) => {
    const key = `i${index}`;
    switch (node.type) {
      case 'break':
        return <br key={key} />;
      case 'code':
        return <code key={key}>{node.value}</code>;
      case 'strong':
        return <strong key={key}>{renderInline(node.children)}</strong>;
      case 'emphasis':
        return <em key={key}>{renderInline(node.children)}</em>;
      case 'text':
      default:
        return <Fragment key={key}>{node.value}</Fragment>;
    }
  });
}

export default HelpChatMarkdown;
