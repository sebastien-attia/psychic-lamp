---
paths: ["frontend/src/**", "frontend/package.json", "frontend/vite.config.ts"]
---
# Frontend Rules
- Vue 3 Composition API <script setup lang="ts"> — never Options API
- Headless UI for accessible primitives, Tailwind CSS for styling
- NO OAuth library — auth handled by BFF (session cookie)
- Login: redirect to /oauth2/authorization/keycloak (BFF endpoint)
- Axios: withCredentials + XSRF-TOKEN cookie + X-XSRF-TOKEN header
- API calls: same origin /api/v1/*, no CORS
- vee-validate + zod for forms, vue-i18n for EN/FR, dark mode with Tailwind
- dev mode (npm run dev): Vite proxy /api → http://localhost:8081 (Business Service)
- local-intg (npm run dev:intg): Vite proxy /api → http://localhost:8080 (BFF)
