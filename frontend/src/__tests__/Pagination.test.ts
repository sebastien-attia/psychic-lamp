import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import Pagination from '../components/ui/Pagination.vue'

/** Default props mirroring the BoatListPage call site. */
function defaultProps(overrides: Partial<Record<string, unknown>> = {}) {
  return {
    currentPage: 0,
    totalPages: 5,
    pageSize: 12,
    pageSizeOptions: [12, 24, 48],
    navLabel: 'Pagination',
    prevLabel: 'Previous',
    nextLabel: 'Next',
    pageSizeLabel: 'Boats per page',
    pageOfLabel: 'Page {current} of {total}',
    pageSizeOptionLabel: '{size} / page',
    ...overrides,
  }
}

describe('Pagination', () => {
  it('emits update:currentPage with the next page when Next is clicked', async () => {
    const wrapper = mount(Pagination, { props: defaultProps({ currentPage: 1 }) })
    // The desktop pill row uses an icon-only button with `aria-label`;
    // the mobile bar uses a text button without `aria-label` (the
    // visible "Next" text serves as the accessible name). Assert
    // exactly one match so a future structural shuffle fails loudly.
    const nextBtns = wrapper.findAll('button[aria-label="Next"]')
    expect(nextBtns).toHaveLength(1)
    await nextBtns[0].trigger('click')
    expect(wrapper.emitted('update:currentPage')).toBeTruthy()
    expect(wrapper.emitted('update:currentPage')![0]).toEqual([2])
  })

  it('emits update:currentPage when a numbered pill is clicked', async () => {
    const wrapper = mount(Pagination, { props: defaultProps({ currentPage: 0 }) })
    const pill = wrapper.findAll('button').find((b) => b.text() === '3')
    expect(pill).toBeTruthy()
    await pill!.trigger('click')
    expect(wrapper.emitted('update:currentPage')![0]).toEqual([2])
  })

  it('does not emit when Previous is clicked on the first page', async () => {
    const wrapper = mount(Pagination, { props: defaultProps({ currentPage: 0 }) })
    const prevBtns = wrapper.findAll('button[aria-label="Previous"]')
    expect(prevBtns).toHaveLength(1)
    await prevBtns[0].trigger('click')
    expect(wrapper.emitted('update:currentPage')).toBeUndefined()
  })

  it('emits update:pageSize when the size selector changes', async () => {
    const wrapper = mount(Pagination, { props: defaultProps() })
    const select = wrapper.find('select')
    await select.setValue('24')
    expect(wrapper.emitted('update:pageSize')![0]).toEqual([24])
  })
})
