import { act } from 'react'
import { createRoot } from 'react-dom/client'
import type { Root } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { CloudImages } from './CloudImages'
import type { CloudImagesProps, CloudImagesState } from './CloudImages'

;(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true

const result = { id: 'res-1', url: 'data:image/png;base64,AAAA', width: 640, height: 480, sha256: 'deadbeefcafe0123', model: 'synthetic-model-x', downloadConfirmed: false }

function makeState(overrides: Partial<CloudImagesState> = {}): CloudImagesState {
  return { available: true, providerLabel: '테스트 제공자', prompt: '  고양이 그림  ', references: [], job: null, result: null, ...overrides }
}

let host: HTMLDivElement
let root: Root
let props: CloudImagesProps

function mount(state: CloudImagesState) {
  props = {
    state, onPromptChange: vi.fn(), onPickReference: vi.fn(), onGenerate: vi.fn(),
    onCancel: vi.fn(), onSave: vi.fn(), onOpenLocalImages: vi.fn(),
  }
  act(() => { root.render(<CloudImages {...props} />) })
}

function button(label: string) {
  const found = [...host.querySelectorAll('button')].find(item => item.textContent?.trim() === label)
  if (!found) throw new Error(`Button missing: ${label}`)
  return found
}

function maybeButton(label: string) {
  return [...host.querySelectorAll('button')].find(item => item.textContent?.trim() === label)
}

beforeEach(() => {
  host = document.createElement('div')
  document.body.append(host)
  root = createRoot(host)
})

afterEach(() => {
  act(() => { root.unmount() })
  host.remove()
  vi.useRealTimers()
})

describe('CloudImages', () => {
  it('reports prompt edits', () => {
    mount(makeState({ prompt: '' }))
    const textarea = host.querySelector('textarea') as HTMLTextAreaElement
    act(() => {
      Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value')?.set?.call(textarea, '새 프롬프트')
      textarea.dispatchEvent(new Event('input', { bubbles: true }))
    })
    expect(props.onPromptChange).toHaveBeenCalledWith('새 프롬프트')
    expect(textarea.maxLength).toBe(2000)
    expect(host.querySelector('label[for="cloud-images-prompt"]')?.textContent).toBe('프롬프트')
  })

  it('disables generate when unavailable and shows the reason', () => {
    mount(makeState({ available: false, blockedReason: '테스트 차단 사유' }))
    expect(host.textContent).toContain('테스트 차단 사유')
    expect(host.querySelector('[role="status"]')).not.toBeNull()
    expect(button('생성').disabled).toBe(true)
  })

  it('shows the default blocked message', () => {
    mount(makeState({ available: false }))
    expect(host.textContent).toContain('지금은 클라우드 이미지 생성을 사용할 수 없습니다.')
  })

  it('disables generate for a blank prompt and enables it otherwise', () => {
    mount(makeState({ prompt: '   ' }))
    expect(button('생성').disabled).toBe(true)
    mount(makeState())
    expect(button('생성').disabled).toBe(false)
  })

  it('disables generate and inputs while a job is active', () => {
    for (const state of ['queued', 'running', 'cancelling', 'unknown'] as const) {
      mount(makeState({ job: { id: 'job-1', state } }))
      expect(button('생성').disabled).toBe(true)
      expect((host.querySelector('textarea') as HTMLTextAreaElement).disabled).toBe(true)
      expect(button('참조 사진 추가').disabled).toBe(true)
    }
    mount(makeState({ job: { id: 'job-1', state: 'failed' } }))
    expect(button('생성').disabled).toBe(false)
  })

  it('submits only once on double click with trimmed prompt and reference ids', () => {
    mount(makeState({ references: [{ id: 'ref-1', previewUrl: 'data:image/png;base64,AA', label: '참조 하나' }, { id: 'ref-2', previewUrl: 'data:image/png;base64,BB', label: '참조 둘' }] }))
    const generate = button('편집해서 생성')
    act(() => { generate.click(); generate.click() })
    expect(props.onGenerate).toHaveBeenCalledTimes(1)
    expect(props.onGenerate).toHaveBeenCalledWith({ prompt: '고양이 그림', referenceIds: ['ref-1', 'ref-2'] })
  })

  it('releases the pending guard after the timeout', () => {
    vi.useFakeTimers()
    mount(makeState())
    act(() => { button('생성').click() })
    expect(button('생성').disabled).toBe(true)
    act(() => { vi.advanceTimersByTime(15001) })
    expect(button('생성').disabled).toBe(false)
  })

  it('shows unknown jobs as unconfirmed, not complete', () => {
    mount(makeState({ job: { id: 'job-1', state: 'unknown' } }))
    expect(host.textContent).toContain('상태 확인 필요')
    expect(host.textContent).toContain('완료로 간주하지 않습니다')
    expect(host.querySelector('figure')).toBeNull()
    expect(host.textContent).not.toContain('완료 보고')
  })

  it('does not show an image when completed without a verified result', () => {
    mount(makeState({ job: { id: 'job-1', state: 'completed' } }))
    expect(host.textContent).toContain('확인하지 못했습니다')
    expect(host.querySelector('img')).toBeNull()
  })

  it('shows failed jobs with error or default text', () => {
    mount(makeState({ job: { id: 'job-1', state: 'failed', error: '합성 오류' } }))
    expect(host.textContent).toContain('합성 오류')
    mount(makeState({ job: { id: 'job-1', state: 'failed' } }))
    expect(host.textContent).toContain('이미지를 만들지 못했습니다.')
  })

  it('shows the result and save state only per downloadConfirmed', () => {
    mount(makeState({ job: { id: 'job-1', state: 'completed' }, result }))
    const image = host.querySelector('figure img') as HTMLImageElement
    expect(image.getAttribute('src')).toBe(result.url)
    expect(host.textContent).toContain('640×480')
    expect(maybeButton('저장됨')).toBeUndefined()
    act(() => { button('저장').click() })
    expect(props.onSave).toHaveBeenCalledWith('res-1')

    mount(makeState({ job: { id: 'job-1', state: 'completed' }, result: { ...result, downloadConfirmed: true } }))
    expect(button('저장됨').disabled).toBe(true)
    expect(maybeButton('저장')).toBeUndefined()
  })

  it('shows cancel only for queued and running jobs', () => {
    for (const state of ['queued', 'running'] as const) {
      mount(makeState({ job: { id: 'job-9', state } }))
      act(() => { button('취소').click() })
      expect(props.onCancel).toHaveBeenCalledWith('job-9')
    }
    for (const state of ['cancelling', 'unknown', 'failed', 'completed'] as const) {
      mount(makeState({ job: { id: 'job-9', state } }))
      expect(maybeButton('취소')).toBeUndefined()
    }
  })

  it('does not render model, hash or job id, nor progress percentages', () => {
    mount(makeState({ job: { id: 'job-secret-42', state: 'running', stageLabel: '단계' }, result }))
    const text = host.textContent ?? ''
    expect(text).not.toContain(result.model)
    expect(text).not.toContain(result.sha256)
    expect(text).not.toContain('job-secret-42')
    expect(text).not.toContain('%')
  })

  it('opens the local images app', () => {
    mount(makeState())
    act(() => { button('로컬 이미지 앱 열기').click() })
    expect(props.onOpenLocalImages).toHaveBeenCalledTimes(1)
  })

  it('exposes layout hooks for the small viewport CSS', () => {
    mount(makeState())
    expect(host.firstElementChild?.classList.contains('cloud-images')).toBe(true)
    expect(host.querySelector('.cloud-images-actions')).not.toBeNull()
  })
})
