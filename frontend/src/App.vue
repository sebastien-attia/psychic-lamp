<script setup lang="ts">
import { RouterView } from 'vue-router'
import MainLayout from './layouts/MainLayout.vue'

/**
 * Root component. Wraps every routed page in the shared `MainLayout`
 * (nav bar, dark-mode toggle, user menu) and renders the matched
 * route via `<RouterView>`.
 */
</script>

<template>
  <MainLayout>
    <RouterView />
  </MainLayout>
</template>
