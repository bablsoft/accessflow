/**
 * Saves a fetched blob to disk via a synthetic anchor.
 *
 * Extracted from the four page-local copies that had accumulated (auditor dashboard, audit log,
 * attestation campaign detail, and now the over-provisioned access report). Every export endpoint
 * returns the same `{ blob, filename }` shape, so the download side has no reason to differ.
 *
 * Note for e2e: because this goes through a Blob URL rather than a navigation, Playwright's
 * `download` event does not fire reliably — assert on the network response instead.
 */
export function downloadBlob({ blob, filename }: { blob: Blob; filename: string }): void {
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = filename;
  document.body.appendChild(anchor);
  anchor.click();
  document.body.removeChild(anchor);
  URL.revokeObjectURL(url);
}
