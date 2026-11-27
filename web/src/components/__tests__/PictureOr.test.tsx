// @vitest-environment jsdom
import { act } from 'react'
import { createRoot } from 'react-dom/client'
import { afterEach, describe, expect, it } from 'vitest'
import { PictureOr } from '../Shelf'

;(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true

const host = document.createElement('div')
const root = createRoot(host)
const show = (src: string) => act(() => root.render(<PictureOr src={src} fallback={<span>tile</span>} />))
const fail = () => act(() => host.querySelector('img')!.dispatchEvent(new Event('error')))

afterEach(() => act(() => root.render(<></>)))

describe('PictureOr', () => {
  it('shows the fallback when the picture fails, and tries each new src again', async () => {
    await show('a.jpg')
    await fail()
    expect(host.textContent).toBe('tile')
    await show('b.jpg')
    expect(host.querySelector('img')?.getAttribute('src')).toBe('b.jpg')
    await show('a.jpg')
    expect(host.querySelector('img')?.getAttribute('src')).toBe('a.jpg')
  })
})
