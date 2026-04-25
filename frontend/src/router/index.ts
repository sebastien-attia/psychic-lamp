import {
  createRouter,
  createWebHistory,
  type RouteRecordRaw,
} from 'vue-router'
import { useAuthStore } from '../stores/auth'

/**
 * Application routes.
 *
 * No `/login` or `/callback` route exists: authentication is handled
 * entirely by the BFF. The router guard below redirects anonymous
 * users to `/oauth2/authorization/keycloak` via `auth.login()`;
 * Spring Security drives the OAuth2 Authorization-Code flow against
 * Keycloak and returns the user to `/` with a session cookie set.
 */
const routes: RouteRecordRaw[] = [
  { path: '/', redirect: '/boats' },
  {
    path: '/boats',
    name: 'boats.list',
    component: () => import('../pages/BoatListPage.vue'),
  },
  {
    path: '/boats/new',
    name: 'boats.create',
    component: () => import('../pages/BoatCreatePage.vue'),
  },
  {
    path: '/boats/:id',
    name: 'boats.detail',
    component: () => import('../pages/BoatDetailPage.vue'),
    props: true,
  },
  {
    path: '/boats/:id/edit',
    name: 'boats.edit',
    component: () => import('../pages/BoatEditPage.vue'),
    props: true,
  },
]

/**
 * Vue Router instance. Uses HTML5 history mode so URLs match the BFF's
 * static-resource fallback (the BFF serves `index.html` for any
 * unmatched path under `/`, letting the SPA take over).
 */
export const router = createRouter({
  history: createWebHistory(),
  routes,
})

/**
 * Global navigation guard: every route in this SPA requires
 * authentication.
 *
 * Fast path (already authenticated, not loading): synchronous
 * short-circuit so subsequent navigations do not introduce a
 * superfluous microtask.
 *
 * On the very first navigation `auth.loading` is still `true` from
 * store creation, so the guard awaits `fetchUser()` and is the single
 * caller responsible for bootstrapping the session. After that,
 * `loading` is `false` and `isAuthenticated` is the source of truth —
 * anonymous users are sent to Keycloak via `auth.login()`, and the
 * navigation is aborted to prevent the destination route from briefly
 * mounting before the browser unloads. `auth.redirecting` short-
 * circuits any further navigation while the redirect is in flight.
 */
router.beforeEach((): boolean | void | Promise<boolean | void> => {
  const auth = useAuthStore()
  if (auth.redirecting) return false
  if (!auth.loading && auth.isAuthenticated) return
  return (async () => {
    if (auth.loading) {
      await auth.fetchUser()
    }
    if (!auth.isAuthenticated) {
      auth.login()
      return false
    }
  })()
})
