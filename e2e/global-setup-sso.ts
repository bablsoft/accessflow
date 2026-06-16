import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';

// Brings the SSO-variant docker stack up before the Playwright run.
// Wired in from playwright.sso.config.ts as `globalSetup`.
//
// The variant stack publishes the backend on host port 8082, the frontend on
// 5175, and the SimpleSAMLphp mock IdP on 8085 — chosen so it can coexist
// with both the main (5173 / 8080) and setup-variant (5174 / 8081) stacks.
// `--wait` blocks until every service's healthcheck reports healthy (or
// fails fast).
export default async function globalSetup(): Promise<void> {
  const here = dirname(fileURLToPath(import.meta.url));
  const composeFile = resolve(here, 'docker-compose.e2e.sso.yml');
  // CI pre-builds + tags the images (buildx + gha cache) and sets E2E_SKIP_BUILD
  // so the stack reuses them; local runs leave it unset and build from source.
  const skipBuild = !!process.env.E2E_SKIP_BUILD;
  const args = ['compose', '-f', composeFile, 'up'];
  if (!skipBuild) args.push('--build');
  args.push('-d', '--wait');
  console.log(
    `[global-setup-sso] bringing up the SSO-variant stack${skipBuild ? ' (reusing pre-built images)' : ''}...`,
  );
  execFileSync('docker', args, { stdio: 'inherit' });
}
