import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import SearchInput from '../components/ui/SearchInput.vue'

const baseProps = {
  modelValue: '',
  placeholder: 'Search boats',
  label: 'Search',
  clearLabel: 'Clear search',
}

describe('SearchInput', () => {
  it('emits update:modelValue when the user types', async () => {
    const wrapper = mount(SearchInput, { props: baseProps })
    await wrapper.find('input[type="search"]').setValue('skippy')
    const events = wrapper.emitted('update:modelValue')
    expect(events).toBeTruthy()
    expect(events![events!.length - 1]).toEqual(['skippy'])
  })

  it('renders the clear button only when the value is non-empty', async () => {
    const wrapper = mount(SearchInput, { props: { ...baseProps, modelValue: '' } })
    expect(wrapper.find('button[aria-label="Clear search"]').exists()).toBe(false)

    await wrapper.setProps({ modelValue: 'skippy' })
    expect(wrapper.find('button[aria-label="Clear search"]').exists()).toBe(true)
  })

  it('emits clear and an empty modelValue when the clear button is clicked', async () => {
    const wrapper = mount(SearchInput, {
      props: { ...baseProps, modelValue: 'skippy' },
    })
    await wrapper.find('button[aria-label="Clear search"]').trigger('click')
    expect(wrapper.emitted('clear')).toBeTruthy()
    const updates = wrapper.emitted('update:modelValue')
    expect(updates).toBeTruthy()
    expect(updates![updates!.length - 1]).toEqual([''])
  })
})
