import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';

// Tears the setup-variant docker stack down after the Playwright run.
// Wired in from playwright.setup.config.ts as `globalTeardown`.
//
// `down -v` drops the Postgres volume so the next `globalSetup` re-boots with
// an empty database and `setup_required: true` again. The variant stack carries
// no production data — same demo defaults as docker-compose.e2e.yml.
export default async function globalTeardown(): Promise<void> {
  const here = dirname(fileURLToPath(import.meta.url));
  const composeFile = resolve(here, 'docker-compose.e2e.setup.yml');
  console.log('[global-teardown-setup] tearing down the setup-variant stack...');
  try {
    execFileSync(
      'docker',
      ['compose', '-f', composeFile, 'down', '-v'],
      { stdio: 'inherit' },
    );
  } catch (err) {
    // Best-effort teardown — log but don't fail the test run on cleanup errors.
    console.error('[global-teardown-setup] teardown failed:', err);
  }
}
