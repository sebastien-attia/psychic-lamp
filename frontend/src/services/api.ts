import { BusinessServiceApi, UserApi } from './api-client/generated/api'
import { http } from './http'

/**
 * Typed client for `/api/v1/boats/**` (CRUD + listing).
 *
 * Wraps the generated `BusinessServiceApi` class and binds it to the shared
 * axios instance from {@link ./http} so all calls flow through the
 * session-cookie + CSRF + RFC 9457 pipeline.
 */
export const boatsApi = new BusinessServiceApi(undefined, '', http)

/**
 * Typed client for `/api/me`.
 *
 * In `dev` mode the Vite proxy answers this with a hardcoded dummy user
 * (see `vite.config.ts`); in `local-intg` and `prod` the BFF answers from
 * the OAuth2 session.
 */
export const userApi = new UserApi(undefined, '', http)
