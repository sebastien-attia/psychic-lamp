import { describe, it, expect, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { nextTick } from 'vue'
import BoatForm from '../components/BoatForm.vue'
import type { BoatResponse } from '../services/api-client/generated/models'

/**
 * vee-validate runs the schema asynchronously and commits the resulting
 * `errors` map across multiple reactivity ticks. A handful of
 * `flushPromises`/`nextTick` cycles is enough to settle the chain in
 * happy-dom; tuned to the slowest path observed (zod-async + emit).
 */
async function settle(): Promise<void> {
  for (let i = 0; i < 10; i++) {
    await flushPromises()
    await nextTick()
    await new Promise((r) => setTimeout(r, 0))
  }
}

/**
 * Build a deterministic `BoatResponse` for the success-path assertion.
 */
function makeSavedBoat(overrides: Partial<BoatResponse> = {}): BoatResponse {
  return {
    id: '00000000-0000-0000-0000-000000000001',
    name: 'My Boat',
    description: null,
    createdAt: '2026-01-01T00:00:00Z',
    version: 0,
    ...overrides,
  } as BoatResponse
}

describe('BoatForm', () => {
  it('shows a required-field error when name is empty on submit', async () => {
    const submit = vi.fn()
    const wrapper = mount(BoatForm, {
      props: { submit, submitLabel: 'Create' },
    })

    await wrapper.find('form').trigger('submit.prevent')
    await settle()

    expect(submit).not.toHaveBeenCalled()
    const error = wrapper.find('#boat-name-error')
    expect(error.exists()).toBe(true)
    expect(error.text()).toContain('required')
  })

  it('caps the name input at 64 characters via the maxlength attribute', () => {
    const wrapper = mount(BoatForm, {
      props: { submit: vi.fn(), submitLabel: 'Create' },
    })
    const input = wrapper.find('#boat-name').element as HTMLInputElement
    expect(input.getAttribute('maxlength')).toBe('64')
    // Mirror the schema contract: a 65-char value would be rejected by
    // Zod (`max(64)`) — the maxlength attribute is the front-line
    // defence so the user can never type more than the schema allows.
    const description = wrapper.find('#boat-description')
      .element as HTMLTextAreaElement
    expect(description.getAttribute('maxlength')).toBe('256')
  })

  it('emits saved with the persisted boat on a successful submit', async () => {
    const persisted = makeSavedBoat({ name: 'Skippy' })
    const submit = vi.fn().mockResolvedValue(persisted)
    const wrapper = mount(BoatForm, {
      props: { submit, submitLabel: 'Create' },
      attachTo: document.body,
    })

    const nameInput = wrapper.find('#boat-name')
    await nameInput.setValue('Skippy')
    await nameInput.trigger('change')
    await nameInput.trigger('blur')
    await settle()

    await wrapper.find('form').trigger('submit.prevent')
    await settle()

    expect(submit).toHaveBeenCalledTimes(1)
    expect(submit).toHaveBeenCalledWith({ name: 'Skippy', description: null })
    expect(wrapper.emitted('saved')).toBeTruthy()
    expect(wrapper.emitted('saved')![0][0]).toEqual(persisted)

    wrapper.unmount()
  })
})
