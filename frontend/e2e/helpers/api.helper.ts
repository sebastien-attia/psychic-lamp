import type { APIRequestContext, BrowserContext } from '@playwright/test';

/**
 * Minimal shape of a `BoatResponse` returned by the Business Service. We
 * mirror only the fields the E2E suite consumes — keeping a separate type
 * here avoids importing the generated OpenAPI client into the test tree.
 */
export interface BoatRecord {
  /** Server-assigned UUID. */
  id: string;
  /** Boat name (≤ 64 chars). */
  name: string;
  /** Optional free-form description (≤ 256 chars). */
  description: string | null;
  /** ISO-8601 creation timestamp in UTC. */
  createdAt: string;
  /** Optimistic-locking version, incremented on each update. */
  version: number;
}

interface PageBoatResponse {
  content: BoatRecord[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
  last: boolean;
}

/**
 * Read the current `XSRF-TOKEN` cookie from the browser context and shape
 * it as the `X-XSRF-TOKEN` header that Spring Security expects on every
 * mutating request.
 *
 * The frontend's axios instance does this translation automatically;
 * Playwright's `request` API does not, so we have to be explicit. Throws
 * with an actionable message when the cookie is missing — most often a
 * sign that the caller has not yet loaded the saved storageState or run
 * `loginAsDemo`. A silent empty object would surface as a confusing 403
 * from Spring's CSRF filter instead.
 */
export async function csrfHeader(
  context: BrowserContext,
): Promise<Record<string, string>> {
  const cookies = await context.cookies();
  const token = cookies.find((c) => c.name === 'XSRF-TOKEN');
  if (!token) {
    throw new Error(
      'XSRF-TOKEN cookie missing — did you load storageState or call loginAsDemo() first?',
    );
  }
  return { 'X-XSRF-TOKEN': token.value };
}

/**
 * Create a single boat through the BFF's `POST /api/v1/boats` endpoint.
 * Throws if the response is not 2xx. Returns the persisted boat.
 */
export async function createBoatViaAPI(
  request: APIRequestContext,
  context: BrowserContext,
  payload: { name: string; description?: string | null },
): Promise<BoatRecord> {
  const response = await request.post('/api/v1/boats', {
    data: { name: payload.name, description: payload.description ?? null },
    headers: { 'Content-Type': 'application/json', ...(await csrfHeader(context)) },
  });
  if (!response.ok()) {
    throw new Error(
      `createBoatViaAPI failed: ${response.status()} ${await response.text()}`,
    );
  }
  return (await response.json()) as BoatRecord;
}

/**
 * Page through `/api/v1/boats` (size=48) and return every boat the current
 * session can see. Used by `deleteAllBoatsViaAPI` and pagination tests
 * that need a full snapshot.
 */
export async function listAllBoatsViaAPI(
  request: APIRequestContext,
): Promise<BoatRecord[]> {
  const all: BoatRecord[] = [];
  let page = 0;
  while (true) {
    const response = await request.get(
      `/api/v1/boats?page=${page}&size=48&sort=createdAt,desc`,
    );
    if (!response.ok()) {
      throw new Error(
        `listAllBoatsViaAPI failed: ${response.status()} ${await response.text()}`,
      );
    }
    const body = (await response.json()) as PageBoatResponse;
    all.push(...body.content);
    if (body.last || body.content.length === 0) {
      return all;
    }
    page += 1;
  }
}

/**
 * Delete every boat reachable by the current session. Called from
 * `beforeEach` / `afterEach` so each test gets a deterministic dataset.
 * Tolerates 404 on individual rows in case a parallel test already removed
 * one — a 5xx still throws so genuine outages do not hide silently.
 */
export async function deleteAllBoatsViaAPI(
  request: APIRequestContext,
  context: BrowserContext,
): Promise<void> {
  const boats = await listAllBoatsViaAPI(request);
  for (const boat of boats) {
    // Re-read the cookie inside the loop in case Spring rotates
    // XSRF-TOKEN between requests (some CSRF token-handler implementations
    // do); the cookie store update is virtually free.
    const headers = await csrfHeader(context);
    const response = await request.delete(`/api/v1/boats/${boat.id}`, { headers });
    if (!response.ok() && response.status() !== 404) {
      throw new Error(
        `deleteAllBoatsViaAPI failed for ${boat.id}: ${response.status()} ${await response.text()}`,
      );
    }
  }
}

/**
 * Seed `count` boats with deterministic names (`<prefix> <index>`) so test
 * assertions can target a specific row by its stable name. Returns the
 * created boats in creation order.
 */
export async function seedBoats(
  request: APIRequestContext,
  context: BrowserContext,
  count: number,
  prefix = 'E2E Boat',
): Promise<BoatRecord[]> {
  const created: BoatRecord[] = [];
  for (let i = 1; i <= count; i += 1) {
    created.push(
      await createBoatViaAPI(request, context, {
        name: `${prefix} ${String(i).padStart(3, '0')}`,
        description: `Seeded for E2E (${i})`,
      }),
    );
  }
  return created;
}
