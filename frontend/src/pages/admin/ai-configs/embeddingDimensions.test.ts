import { describe, expect, it } from 'vitest';
import { clearableDimensions } from './embeddingDimensions';

const base = {
  rag_enabled: true,
  embedding_provider: 'OPENAI' as const,
  embedding_dimensions: 512,
};

describe('clearableDimensions', () => {
  it('sends the chosen width for a provider that can honour it', () => {
    expect(clearableDimensions(base)).toBe(512);
    expect(clearableDimensions({ ...base, embedding_provider: 'VOYAGE' })).toBe(512);
  });

  it('preserves a width set outside the UI — the field is mounted, so blank means cleared', () => {
    // The regression this guards: when the input was Voyage-only, every OpenAI save sent 0 and wiped
    // a dimension an operator had set over the API.
    expect(clearableDimensions({ ...base, embedding_dimensions: 1536 })).toBe(1536);
  });

  it('clears when the admin empties the field', () => {
    expect(clearableDimensions({ ...base, embedding_dimensions: null })).toBe(0);
  });

  it('clears for a provider with no dimension knob', () => {
    expect(clearableDimensions({ ...base, embedding_provider: 'OLLAMA' })).toBe(0);
  });

  it('clears when RAG is switched off or no provider is chosen', () => {
    expect(clearableDimensions({ ...base, rag_enabled: false })).toBe(0);
    expect(clearableDimensions({ ...base, embedding_provider: null })).toBe(0);
  });
});
