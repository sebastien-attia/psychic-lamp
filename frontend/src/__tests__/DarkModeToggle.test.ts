import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import DarkModeToggle from '../components/DarkModeToggle.vue'
import {
  useDarkMode,
  readPersistedTheme,
} from '../composables/useDarkMode'

describe('DarkModeToggle', () => {
  it('mounts and exposes the `useDarkMode` initial state (light)', () => {
    const wrapper = mount(DarkModeToggle)
    // Assert against the composable, not Headless UI's `<Switch>`
    // internal attributes — the latter has flipped between
    // `aria-checked` and `data-state` across major versions.
    expect(useDarkMode().isDark.value).toBe(false)
    // Sanity-check that the button element is rendered (Switch
    // renders as a `<button>`).
    expect(wrapper.find('button').exists()).toBe(true)
  })

  it('flips the <html> dark class and writes the cookie when toggled', async () => {
    const wrapper = mount(DarkModeToggle, { attachTo: document.body })
    expect(document.documentElement.classList.contains('dark')).toBe(false)
    expect(readPersistedTheme()).toBe(null)

    await wrapper.find('button').trigger('click')

    expect(document.documentElement.classList.contains('dark')).toBe(true)
    expect(readPersistedTheme()).toBe('dark')
    expect(useDarkMode().isDark.value).toBe(true)

    wrapper.unmount()
  })

  it('toggles back to light on a second click', async () => {
    const wrapper = mount(DarkModeToggle, { attachTo: document.body })
    const btn = wrapper.find('button')
    await btn.trigger('click')
    await btn.trigger('click')
    expect(document.documentElement.classList.contains('dark')).toBe(false)
    expect(readPersistedTheme()).toBe('light')
    expect(useDarkMode().isDark.value).toBe(false)

    wrapper.unmount()
  })
})
