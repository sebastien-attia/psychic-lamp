import {
  createRouter,
  createWebHistory,
  type RouteRecordRaw,
} from 'vue-router'
import { useAuthStore } from '../stores/auth'
import { ApiProblemError } from '../services/problem-detail'

let bootstrapped = false

/**
 * Application routes.
 *
 * No `/login` or `/callback` route exists: authentication is handled
 * entirely by the BFF (the axios 401 interceptor redirects to
 * `/oauth2/authorization/keycloak`, Spring Security drives the OAuth2
 * Authorization-Code flow against Keycloak, and the user is returned to
 * `/` with a session cookie set).
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
 * static-resource fallback (the BFF serves `index.html` for any unmatched
 * path under `/`, letting the SPA take over).
 */
export const router = createRouter({
  history: createWebHistory(),
  routes,
})

/**
 * Global navigation guard: ensure the current user is loaded once before any
 * navigation completes. The first call awaits `GET /api/me`; subsequent
 * navigations short-circuit on the module-scoped `bootstrapped` flag so a
 * transient backend error does not pin the guard into an infinite refetch
 * loop.
 *
 * On 401 the axios interceptor in `services/http.ts` triggers a redirect to
 * Keycloak; the rejection from `fetchMe()` is then swallowed here (the page
 * is unloading anyway). Any other failure is rethrown so the user sees
 * something instead of landing on a blank screen.
 */
router.beforeEach(async () => {
  if (bootstrapped) return
  const auth = useAuthStore()
  try {
    await auth.fetchMe()
  } catch (e) {
    if (e instanceof ApiProblemError && e.status === 401) {
      // 401 already triggered a redirect to Keycloak; swallow.
      return
    }
    throw e
  } finally {
    bootstrapped = true
  }
})
