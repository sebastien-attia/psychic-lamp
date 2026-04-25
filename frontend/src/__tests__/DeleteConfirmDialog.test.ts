import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import DeleteConfirmDialog from '../components/DeleteConfirmDialog.vue'

/**
 * Locate the destructive-action button inside the Headless UI Dialog
 * (rendered into `document.body` via teleport). The danger tone gives
 * the button the unique `bg-red-600` Tailwind class, so we filter on
 * that rather than visible text — that keeps the test stable across
 * locale changes and translation edits.
 */
function findConfirmButton(): HTMLButtonElement | null {
  return Array.from(
    document.querySelectorAll<HTMLButtonElement>('button.bg-red-600'),
  )[0] ?? null
}

/**
 * Locate the dismissive button. Cancel is the only neutral-styled
 * button inside the dialog (white background, slate border) and is
 * also the only `button[type="button"]` whose class includes
 * `border-slate-300`.
 */
function findCancelButton(): HTMLButtonElement | null {
  return Array.from(
    document.querySelectorAll<HTMLButtonElement>(
      'button.border-slate-300[type="button"]',
    ),
  )[0] ?? null
}

describe('DeleteConfirmDialog', () => {
  it('renders nothing when open=false', () => {
    const wrapper = mount(DeleteConfirmDialog, {
      props: { open: false, boatName: 'Skippy' },
      attachTo: document.body,
    })
    const dialog = document.querySelector('[role="dialog"]')
    expect(dialog).toBeNull()
    wrapper.unmount()
  })

  it('renders title with boat name when open=true', async () => {
    const wrapper = mount(DeleteConfirmDialog, {
      props: { open: true, boatName: 'Skippy' },
      attachTo: document.body,
    })
    await new Promise((r) => setTimeout(r, 0))
    const text = document.body.textContent ?? ''
    expect(text).toContain('Skippy')
    wrapper.unmount()
  })

  it('emits confirm when the destructive button is clicked', async () => {
    const wrapper = mount(DeleteConfirmDialog, {
      props: { open: true, boatName: 'Skippy' },
      attachTo: document.body,
    })
    await new Promise((r) => setTimeout(r, 0))
    const confirmBtn = findConfirmButton()
    expect(confirmBtn).toBeTruthy()
    confirmBtn!.click()
    expect(wrapper.emitted('confirm')).toBeTruthy()
    wrapper.unmount()
  })

  it('emits cancel when the cancel button is clicked', async () => {
    const wrapper = mount(DeleteConfirmDialog, {
      props: { open: true, boatName: 'Skippy' },
      attachTo: document.body,
    })
    await new Promise((r) => setTimeout(r, 0))
    const cancelBtn = findCancelButton()
    expect(cancelBtn).toBeTruthy()
    cancelBtn!.click()
    expect(wrapper.emitted('cancel')).toBeTruthy()
    wrapper.unmount()
  })
})
