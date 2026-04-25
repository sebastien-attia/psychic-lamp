<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import ConfirmDialog from './ConfirmDialog.vue'

/**
 * Boat-specific delete-confirmation dialog.
 *
 * Thin wrapper over {@link ConfirmDialog} that formats the standard
 * "Delete «{boatName}»?" title and "This action cannot be undone."
 * body from a single `boatName` prop, so call sites do not duplicate
 * the i18n lookup or the punctuation. The destructive tone, button
 * label, and loading state are wired through to the underlying
 * dialog.
 */
const props = defineProps<{
  /** Whether the dialog is visible. */
  open: boolean
  /** Name of the boat being deleted; interpolated into the title. */
  boatName: string
  /**
   * Disables the confirm button and renders a spinner while the
   * `DELETE` request is in flight.
   */
  loading?: boolean
}>()

const emit = defineEmits<{
  /** User dismissed the dialog (Escape, backdrop, or Cancel). */
  (e: 'cancel'): void
  /** User confirmed the deletion. */
  (e: 'confirm'): void
}>()

const { t } = useI18n()

/** Localized "Delete «{name}»?" title. */
const title = computed(() => t('boats.delete.title', { name: props.boatName }))
</script>

<template>
  <ConfirmDialog
    :open="open"
    :title="title"
    :message="t('boats.delete.message')"
    :loading="loading"
    :confirm-label="t('boats.delete.button')"
    tone="danger"
    @cancel="emit('cancel')"
    @confirm="emit('confirm')"
  />
</template>
