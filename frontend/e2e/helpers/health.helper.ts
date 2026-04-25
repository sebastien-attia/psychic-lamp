/**
 * Polling helpers for service readiness checks. Used by Playwright's
 * globalSetup to verify the docker-compose stack is up before any tests run.
 */

export interface WaitForHealthyOptions {
  /** Total budget in milliseconds before giving up. Defaults to 60_000. */
  timeoutMs?: number;
  /** Delay between polls in milliseconds. Defaults to 2_000. */
  intervalMs?: number;
  /** Predicate run on the response body; if omitted, any 2xx counts as healthy. */
  predicate?: (status: number, body: string) => boolean;
}

/**
 * Polls a URL until the predicate accepts the response, the timeout elapses,
 * or the host stays unreachable. Returns when healthy; throws otherwise with
 * a message that names the URL and tells the caller to start the stack.
 */
export async function waitForHealthy(url: string, opts: WaitForHealthyOptions = {}): Promise<void> {
  const timeoutMs = opts.timeoutMs ?? 60_000;
  const intervalMs = opts.intervalMs ?? 2_000;
  const predicate = opts.predicate ?? ((status) => status >= 200 && status < 300);

  const deadline = Date.now() + timeoutMs;
  let lastError = '';

  while (Date.now() < deadline) {
    try {
      const response = await fetch(url, { redirect: 'manual' });
      const body = await response.text();
      if (predicate(response.status, body)) {
        return;
      }
      lastError = `HTTP ${response.status} from ${url} (body: ${truncate(body, 200)})`;
    } catch (error) {
      lastError = `${(error as Error).message} (${url})`;
    }
    await sleep(intervalMs);
  }

  throw new Error(
    `Service at ${url} never became healthy within ${timeoutMs}ms. ` +
      `Last status: ${lastError}. Did you run \`docker compose up\` from the repo root?`,
  );
}

/**
 * Predicate that matches Spring Boot Actuator's `{"status":"UP"}` payload.
 * Use it as the `predicate` in `waitForHealthy` for actuator endpoints.
 */
export function actuatorUp(status: number, body: string): boolean {
  return status === 200 && body.includes('"status":"UP"');
}

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function truncate(value: string, max: number): string {
  return value.length <= max ? value : `${value.slice(0, max)}…`;
}
