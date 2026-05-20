import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';

// Brings the setup-variant docker stack up before the Playwright run.
// Wired in from playwright.setup.config.ts as `globalSetup`.
//
// The variant stack publishes the backend on host port 8081 and the frontend on
// 5174 — chosen so it can coexist with the main docker-compose.e2e.yml stack
// (5173 / 8080). `--wait` blocks until every service's healthcheck reports
// healthy (or fails fast).
//
// We resolve the compose file relative to this script rather than CWD so the
// hook works regardless of where Playwright is invoked from.
export default async function globalSetup(): Promise<void> {
  const here = dirname(fileURLToPath(import.meta.url));
  const composeFile = resolve(here, 'docker-compose.e2e.setup.yml');
  console.log('[global-setup-setup] bringing up the setup-variant stack...');
  execFileSync(
    'docker',
    ['compose', '-f', composeFile, 'up', '--build', '-d', '--wait'],
    { stdio: 'inherit' },
  );
}
