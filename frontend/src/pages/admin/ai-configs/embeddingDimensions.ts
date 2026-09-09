import { DIMENSION_CAPABLE_PROVIDERS } from '@/utils/enumLabels';
import type { AiProvider } from '@/types/api';

/**
 * What the edit page sends for `embedding_dimensions` (AF-918): the chosen width, or `0` to clear
 * the stored one. `null`/absent would mean "leave it alone", which is the wrong answer here.
 *
 * The subtlety this exists to pin down: `RagFormSection` mounts the input only for a provider that
 * can honour a width, and an unmounted AntD `Form.Item` is absent from `onFinish`'s values. If the
 * field were Voyage-only, a blank value would be ambiguous — "the admin cleared it" or "I could not
 * see it" — and clearing on the second reading silently wipes a dimension set over the API or by
 * Terraform the first time an admin edits anything else on that row. Mounting it for every capable
 * provider is what makes the blank unambiguous; this function is the other half of that contract.
 */
export function clearableDimensions(values: {
  rag_enabled: boolean;
  embedding_provider: AiProvider | null;
  embedding_dimensions: number | null;
}): number {
  if (!values.rag_enabled || values.embedding_provider === null) {
    return 0;
  }
  if (!DIMENSION_CAPABLE_PROVIDERS.includes(values.embedding_provider)) {
    // The provider cannot honour a width, so a stored one can only mislead the pgvector check.
    return 0;
  }
  return values.embedding_dimensions ?? 0;
}
