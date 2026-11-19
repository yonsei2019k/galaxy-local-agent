import { useId, useLayoutEffect, useRef, useState } from 'react'
import type { ReactNode } from 'react'
import { Icon } from './Icon'
import type { IconName } from './Icon'
import { BrandMark } from './Conversation'
import type { Panel, Snapshot } from './bridge'
import { runControls } from './composer'

const panels: { panel: Panel; label: string; icon: IconName }[] = [
  { panel: 'collab', label: '협업방', icon: 'group' },
  { panel: 'projects', label: '프로젝트', icon: 'folder' },
  { panel: 'images', label: '이미지 작업실', icon: 'image' },
  { panel: 'tools', label: '도구', icon: 'tools' },
  { panel: 'history', label: '대화 기록', icon: 'history' },
  { panel: 'settings', label: '설정', icon: 'settings' },
]

export function Sidebar({ snapshot, enabled, collaborationOpen, onSelect, onNew, onNavigate, onDetails, onClose }: {
  snapshot: Snapshot | null
  enabled: boolean
  collaborationOpen: boolean
  onSelect(id: string): void
  onNew(): void
  onNavigate(panel: Panel): void
  onDetails(): void
  onClose?(): void
}) {
  const threads = snapshot?.threads ?? []
  return (
    <div className="sidebar-content">
      <div className="sidebar-brand">
        <span className="wordmark"><BrandMark /> ZipHap<span>.Agent</span></span>
        {onClose && <button type="button" className="icon-button" aria-label="사이드바 닫기" onClick={onClose}><Icon name="close" /></button>}
      </div>
      <button type="button" className="new-thread" disabled={!enabled} onClick={onNew}><Icon name="plus" /> 새 대화</button>
      <nav className="thread-nav" aria-label="대화 목록">
        <h2>대화</h2>
        {threads.length > 0 ? <ul>{threads.map(thread => (
          <li key={thread.id}>
            <button type="button" className="sidebar-row" aria-current={thread.selected && !collaborationOpen ? 'page' : undefined}
              disabled={!enabled} onClick={() => onSelect(thread.id)}>
              <Icon name="chat" /><span>{thread.title || '새 대화'}</span>
            </button>
          </li>
        ))}</ul> : snapshot?.ready && <p className="no-threads">대화가 없습니다.</p>}
      </nav>
      <nav className="utility-nav" aria-label="앱 메뉴">
        {panels.map(({ panel, label, icon }) => (
          <button key={panel} type="button" className="sidebar-row" disabled={!enabled}
            aria-current={panel === 'collab' && collaborationOpen ? 'page' : undefined} onClick={() => onNavigate(panel)}>
            <Icon name={icon} /><span>{label}</span>
          </button>
        ))}
        <button type="button" className="sidebar-row" disabled={!enabled} onClick={onDetails}><Icon name="native" /><span>기존 화면</span></button>
      </nav>
    </div>
  )
}

export function MobileSidebar({ open, onClose, children }: { open: boolean; onClose(): void; children: ReactNode }) {
  const dialog = useRef<HTMLDialogElement>(null)
  useLayoutEffect(() => {
    const element = dialog.current
    if (!element) return
    if (open && !element.open) element.showModal()
    if (!open && element.open) element.close()
  }, [open])

  return <dialog ref={dialog} className="mobile-sidebar" aria-label="대화 및 앱 메뉴"
    onClose={onClose} onClick={event => { if (event.target === event.currentTarget) onClose() }}>
    {open && children}
  </dialog>
}

export function Header({ snapshot, enabled, onMenu, onSelectModel, onRename }: {
  snapshot: Snapshot | null
  enabled: boolean
  onMenu(): void
  onSelectModel(id: string): void
  onRename(title: string): boolean
}) {
  const nextModel = snapshot?.models.find(model => model.id === snapshot.modelId)
  const selectedLabel = snapshot?.modelLabel || nextModel?.label || '모델 선택'
  const activeLabel = runControls(snapshot).active ? snapshot?.activeModelLabel : undefined
  const currentThread = snapshot?.threads.find(thread => thread.selected)
  const [titleDraft, setTitleDraft] = useState<string | null>(null)

  return (
    <header className="topbar">
      <div className="topbar-context">
        <button type="button" className="icon-button menu-button" aria-label="대화 및 앱 메뉴 열기" onClick={onMenu}><Icon name="menu" /></button>
        <span className="topbar-title" title={currentThread?.title}>{currentThread?.title || 'ZipHap.Agent'}</span>
        {currentThread && titleDraft === null && <button type="button" className="rename-thread" disabled={!enabled}
          onClick={() => setTitleDraft(currentThread.title)}>이름 변경</button>}
        {titleDraft !== null && <form className="rename-thread-form" onSubmit={event => {
          event.preventDefault()
          const title = titleDraft.trim()
          if (enabled && title && onRename(title)) setTitleDraft(null)
        }}>
          <input aria-label="대화 이름" autoFocus value={titleDraft} maxLength={128}
            onChange={event => setTitleDraft(event.target.value)} />
          <button type="submit" disabled={!enabled || !titleDraft.trim()}>저장</button>
          <button type="button" onClick={() => setTitleDraft(null)}>취소</button>
        </form>}
      </div>
      <div className="model-group">
        {activeLabel && <span className="active-model" title={`실행 중인 모델 ${activeLabel}`}><span />{activeLabel}</span>}
        <label className="model-control" title={`다음 요청 모델 ${selectedLabel}`}>
          <Icon name="chip" />
          <select value={nextModel?.id ?? ''} disabled={!enabled || !snapshot?.models.length}
            aria-label="다음 요청 모델" onChange={event => onSelectModel(event.currentTarget.value)}>
            {!nextModel && <option value="" disabled>{selectedLabel}</option>}
            {snapshot?.models.map(model => <option key={model.id} value={model.id}>{model.id === nextModel?.id ? selectedLabel : model.label}</option>)}
          </select>
          <Icon className="model-chevron" name="down" />
        </label>
      </div>
    </header>
  )
}

const MAX_REQUEST_LENGTH = 12_000
const MAX_STEER_LENGTH = 4_000

export function Composer({ snapshot, enabled, draft, pending, status, onDraft, onSubmit, onAttach, onStop, onDetails }: {
  snapshot: Snapshot | null
  enabled: boolean
  draft: string
  pending: boolean
  status: string
  onDraft(text: string): void
  onSubmit(kind: 'send' | 'steer'): void
  onAttach(): void
  onStop(): void
  onDetails(): void
}) {
  const statusId = useId()
  const hasText = draft.trim().length > 0
  const attachments = snapshot?.ready ? snapshot.attachments ?? [] : []
  const controls = runControls(snapshot)
  const busy = enabled && controls.active
  const canStop = enabled && controls.stop !== null
  const canSteer = enabled && controls.steer !== null && attachments.length === 0
  const tooLong = draft.length > MAX_REQUEST_LENGTH
  const tooLongForSteer = draft.length > MAX_STEER_LENGTH
  const sendReady = enabled && !pending && !tooLong && (hasText || attachments.length > 0)
  const sendLabel = busy ? '대기열에 추가' : '보내기'
  const note = tooLong ? '12,000자를 넘는 요청은 파일로 첨부해 주세요.' : status

  return (
    <footer className="composer-footer">
      <div className="composer-width">
        <div className="composer-status" id={statusId} role="status">{note}</div>
        <form className="composer" aria-label="메시지 작성" onSubmit={event => {
          event.preventDefault()
          if (sendReady) onSubmit('send')
        }}>
          {attachments.length > 0 && <div className="attachments" aria-label="첨부 파일">
            {attachments.map(attachment => <span className="attachment" key={attachment.id} title={attachment.name}><Icon name="file" /><span>{attachment.name}</span></span>)}
            <button type="button" className="icon-button icon-button--compact" aria-label="첨부 파일 관리" title="첨부 파일 관리" disabled={!enabled} onClick={onDetails}><Icon name="open" /></button>
          </div>}
          <div className="editor">
            <div className="editor-size" aria-hidden="true">{draft}{'\n'}</div>
            <textarea aria-label="메시지" aria-describedby={note ? statusId : undefined} aria-invalid={tooLong || undefined}
              placeholder="메시지를 입력하세요" dir="auto" rows={1} disabled={!enabled} value={draft}
              onChange={event => onDraft(event.currentTarget.value)} onKeyDown={event => {
                if (event.key === 'Enter' && (event.ctrlKey || event.metaKey) && !event.nativeEvent.isComposing &&
                  event.nativeEvent.keyCode !== 229 && !event.repeat) {
                  event.preventDefault()
                  if (sendReady) onSubmit('send')
                }
              }} />
          </div>
          <div className="composer-tools">
            <button type="button" className="icon-button attach-button" disabled={!enabled} aria-label="파일 첨부" title="파일 첨부" onClick={onAttach}><Icon name="clip" /></button>
            <div className="composer-actions">
              {canStop && <button type="button" className="icon-button stop-button" aria-label="현재 작업 중단" title="현재 작업 중단" onClick={onStop}><Icon name="stop" /></button>}
              {canSteer && <button type="button" className="steer-button" disabled={!sendReady || !hasText || tooLongForSteer}
                aria-label="추가 지시" title={tooLongForSteer ? '추가 지시는 4,000자까지 입력할 수 있어요.' : '실행 중인 작업에 추가 지시'} onClick={() => onSubmit('steer')}>
                <Icon name="steer" /><span>추가 지시</span>
              </button>}
              <button type="submit" className="send-button" disabled={!sendReady} title={`${sendLabel} (Ctrl / ⌘ + Enter)`} aria-label={sendLabel}>
                {busy && <span>{sendLabel}</span>}<Icon name={busy ? 'plus' : 'arrow'} />
              </button>
            </div>
          </div>
        </form>
      </div>
    </footer>
  )
}
