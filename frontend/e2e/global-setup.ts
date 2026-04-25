import { actuatorUp, waitForHealthy } from './helpers/health.helper';

/**
 * Playwright global setup — verifies that the local-intg docker compose
 * stack is up before any tests run. Polls:
 *   - BFF actuator health         (http://localhost:8080/actuator/health)
 *   - Keycloak realm discovery    (http://localhost:8180/realms/boat-app/.well-known/openid-configuration)
 *
 * Throws with a clear message if either probe stays red — the developer
 * forgot to `docker compose up` and we want to fail fast, not after a
 * 30-second test timeout.
 */
export default async function globalSetup(): Promise<void> {
  await waitForHealthy('http://localhost:8080/actuator/health', { predicate: actuatorUp });
  await waitForHealthy('http://localhost:8180/realms/boat-app/.well-known/openid-configuration');
}
