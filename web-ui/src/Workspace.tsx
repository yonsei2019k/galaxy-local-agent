import { useEffect, useId, useLayoutEffect, useRef, useState } from 'react'
import type { ReactNode } from 'react'
import { Icon } from './Icon'
import type { IconName } from './Icon'
import { Character } from './character/Character'
import { CharacterPicker } from './character/CharacterPicker'
import { useCharacter } from './character/useCharacter'
import { situationForMood, useCharacterMood } from './character/useCharacterMood'
import type { Panel, Snapshot } from './bridge'
import { runControls } from './composer'
import { ComposerSheet } from './ComposerSheet'
import { HeaderWorkLine } from './work/HeaderWorkLine'
import { StickerText, withoutEmoji } from './icons/Sticker'
import { QuickJobs } from './QuickJobs'

const shortcuts: { panel: Panel; label: string; icon: IconName }[] = [
  { panel: 'capabilities', label: '할 수 있는 일', icon: 'activity' },
  { panel: 'services', label: '앱과 서비스', icon: 'group' },
  { panel: 'images', label: '이미지 만들기', icon: 'image' },
  { panel: 'settings', label: '설정', icon: 'settings' },
]

const mainDestinations: { panel: Panel; label: string; icon: IconName }[] = [
  { panel: 'chat', label: '기본 채팅', icon: 'chat' },
  { panel: 'contacts', label: '연락처', icon: 'group' },
  { panel: 'capabilities', label: '둘러보기', icon: 'compass' },
  { panel: 'history', label: '보관함', icon: 'archive' },
  { panel: 'settings', label: '나의 집순이', icon: 'person' },
]

export function BottomNavigation({ enabled, onNavigate }: { enabled: boolean; onNavigate(panel: Panel): void }) {
  return <nav className="bottom-nav" aria-label="빠른 이동">
    {mainDestinations.filter(item => item.panel !== 'contacts').map(({ panel, label, icon }) =>
      <button key={panel} type="button" disabled={!enabled} aria-current={panel === 'chat' ? 'page' : undefined}
        onClick={() => onNavigate(panel)}><Icon name={icon} /><span>{label}</span></button>
    )}
  </nav>
}

export function Sidebar({ snapshot, enabled, collaborationOpen, onSelect, onOpenOffice, onNavigate, onClose, onChooseJob, jobsDisabled }: {
  snapshot: Snapshot | null
  enabled: boolean
  collaborationOpen: boolean
  onSelect(id: string): void
  onOpenOffice(): void
  onNavigate(panel: Panel): void
  onClose?(): void
  onChooseJob?(text: string): void
  jobsDisabled?: boolean
}) {
  const [character] = useCharacter()
  const staff = snapshot?.staffRooms
  const threads = staff ?? snapshot?.threads ?? []
  const single = snapshot?.singleConversation === true
  return (
    <div className="sidebar-content">
      <div className="sidebar-brand">
        <span className="wordmark"><Character choice={character} size={32} decorative /> {single ? <span className="wordmark-display">집순이</span> : <>ZipHap<span className="wordmark-sub">.Agent</span></>}</span>
        {single && <button type="button" className="icon-button sidebar-search" aria-label="대화 검색" disabled={!enabled}
          onClick={() => onNavigate('historySearch')}><Icon name="search" /></button>}
        {onClose && <button type="button" className="icon-button" aria-label="사이드바 닫기" onClick={onClose}><Icon name="close" /></button>}
      </div>
      {!single && <><nav className="shared-rooms" aria-label="메인 채팅">
        <button type="button" className="sidebar-row" disabled={!enabled}
          aria-current={snapshot?.officeSelected && !collaborationOpen ? 'page' : undefined} onClick={onOpenOffice}>
          <Icon name="group" /><span>사무실</span><Icon name="chevronRight" className="row-chevron" />
        </button>
      </nav>
      <nav className={`thread-nav${staff ? ' staff-rooms' : ''}`} aria-label={staff ? '직원 채팅방' : '대화 목록'}>
        <h2>{staff ? '직원' : '대화 목록'}</h2>
        {threads.length > 0 ? <ul>{threads.map(thread => (
          <li key={thread.id}>
            <button type="button" className="sidebar-row" aria-current={thread.selected && !collaborationOpen ? 'page' : undefined}
              disabled={!enabled} onClick={() => onSelect(thread.id)}>
              <Icon name="chat" /><span><StickerText text={thread.title || '제목 없는 대화'} /></span>
              {thread.working && <span className="room-working" role="status" aria-label="작업 중" title="작업 중" />}
              <Icon name="chevronRight" className="row-chevron" />
            </button>
          </li>
        ))}</ul> : snapshot?.ready && <p className="no-threads">대화가 없습니다.</p>}
      </nav>
      <nav className="shared-rooms" aria-label="외부 협업">
        <button type="button" className="sidebar-row" disabled={!enabled}
          aria-current={collaborationOpen ? 'page' : undefined} onClick={() => onNavigate('collab')}>
          <Icon name="group" /><span>협력사 핫라인</span><Icon name="chevronRight" className="row-chevron" />
        </button>
      </nav>
      </>}
      <nav className={single ? "destination-nav" : "utility-nav"} aria-label="앱 메뉴">
        {(single ? mainDestinations : shortcuts).map(({ panel, label, icon }) => (
          <button key={panel} type="button" className="sidebar-row" disabled={!enabled}
            aria-current={single && panel === 'chat' ? 'page' : undefined} onClick={() => onNavigate(panel)}>
            <Icon name={icon} /><span>{label}</span><Icon name="chevronRight" className="row-chevron" />
          </button>
        ))}
        {single && <button type="button" className="sidebar-row" disabled={!enabled} onClick={() => onNavigate('services')}>
          <Icon name="group" /><span>앱과 서비스</span><Icon name="chevronRight" className="row-chevron" />
        </button>}
      </nav>
      {single && onChooseJob && <QuickJobs disabled={jobsDisabled === true} onChoose={onChooseJob} />}
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

export function Header({ snapshot, enabled, onMenu, onManage, onRename, onOpenWork }: {
  snapshot: Snapshot | null
  enabled: boolean
  onMenu(): void
  onManage(): void
  onRename(title: string): boolean
  onOpenWork(): void
}) {
  const currentThread = snapshot?.threads.find(thread => thread.selected)
  const [titleDraft, setTitleDraft] = useState<string | null>(null)
  const [character, setCharacter] = useCharacter()
  const [pickerOpen, setPickerOpen] = useState(false)
  const mood = useCharacterMood(snapshot)

  return (
    <header className="topbar">
      <div className="topbar-context">
        <button type="button" className="icon-button menu-button" aria-label="대화 및 앱 메뉴 열기" onClick={onMenu}><Icon name="menu" /></button>
        {snapshot?.singleConversation && <>
          <button type="button" className="character-button" data-mood={mood} aria-label="집순이 모습 바꾸기" onClick={() => setPickerOpen(true)}>
            <Character choice={character} situation={situationForMood(mood)} size={32} decorative />
            {mood === 'thinking' && <span className="character-thinking" aria-hidden="true"><i /><i /><i /></span>}
            {mood === 'done' && <span className="character-burst" aria-hidden="true"><i /><i /><i /><i /><i /></span>}
          </button>
          <CharacterPicker open={pickerOpen} value={character} onChange={setCharacter} onClose={() => setPickerOpen(false)} />
        </>}
        <div className="topbar-heading">
          <span className="topbar-title" title={currentThread?.title ? withoutEmoji(currentThread.title) : undefined}><StickerText text={currentThread?.title || '집순이'} /></span>
          <HeaderWorkLine snapshot={snapshot} onOpen={onOpenWork} />
        </div>
        {snapshot?.singleConversation !== true && currentThread && titleDraft === null && <button type="button" className="rename-thread" disabled={!enabled}
          onClick={() => setTitleDraft(currentThread.title)}><Icon name="edit" /><span className="rename-thread-label">이름 변경</span></button>}
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
      <div className="topbar-actions">
        <button type="button" className="icon-button" aria-label="이 대화" title="이 대화" disabled={!enabled} onClick={onManage}><Icon name="chat" /></button>
      </div>
    </header>
  )
}

const STATUS_VISIBLE_MS = 6_000

/** A host status line that appears when it changes and goes away on its own; the text present on mount is not new. */
function useBriefStatus(status: string) {
  const [shown, setShown] = useState('')
  const seen = useRef(status)
  useEffect(() => {
    if (status === seen.current) return
    seen.current = status
    setShown(status)
    if (!status) return
    const timer = window.setTimeout(() => setShown(''), STATUS_VISIBLE_MS)
    return () => window.clearTimeout(timer)
  }, [status])
  return shown
}

const MAX_REQUEST_LENGTH = 12_000
const MAX_STEER_LENGTH = 4_000

export function Composer({ snapshot, enabled, draft, pending, connectionNote, onDraft, onSubmit, onAttach, onSelectModel, onStop, onRemoveAttachment,
  onCancelPreparation, onOpenWaitingApproval, onVoice, onCancelVoice, onOpenTalk }: {
  snapshot: Snapshot | null
  enabled: boolean
  draft: string
  pending: boolean
  /** Only a problem reaching the app; ordinary work status is shown by the header work line. */
  connectionNote: string
  onDraft(text: string): void
  onSubmit(kind: 'send' | 'steer'): void
  onAttach(): void
  onSelectModel(id: string): void
  onOpenTalk?(): void
  onVoice(): void
  onCancelVoice(): void
  onStop(): void
  onRemoveAttachment(id: string): void
  onCancelPreparation(): void
  onOpenWaitingApproval(): void
}) {
  const statusId = useId()
  const [sheetOpen, setSheetOpen] = useState(false)
  const briefStatus = useBriefStatus(snapshot?.ready ? snapshot.status : '')
  const hasText = draft.trim().length > 0
  const attachments = snapshot?.ready ? snapshot.attachments ?? [] : []
  const controls = runControls(snapshot)
  const busy = enabled && controls.active
  const canStop = enabled && controls.stop !== null
  const canSteer = enabled && controls.steer !== null && attachments.length === 0
  const tooLong = draft.length > MAX_REQUEST_LENGTH
  const tooLongForSteer = draft.length > MAX_STEER_LENGTH
  const voice = snapshot?.voicePrompt
  const voiceBusy = voice !== undefined
  const voiceLabel = voice === 'listening' ? '말하기 완료' : '음성으로 입력'
  const sendReady = enabled && !pending && !voiceBusy && !tooLong && (hasText || attachments.length > 0)
  const sendLabel = busy ? '다음에 보내기' : '보내기'
  const voiceStatus = voice === 'listening' ? '듣고 있어요. 다 말했으면 마이크를 다시 누르세요.'
    : voice === 'finishing' ? '음성을 글로 바꾸고 있어요.' : voice === 'permission' ? '마이크 권한을 확인하고 있어요.' : ''
  const note = voiceStatus || (tooLong ? '12,000자를 넘는 요청은 파일로 첨부해 주세요.' : connectionNote || briefStatus)

  return (
    <footer className="composer-footer">
      <div className="composer-width">
        <div className="composer-status" id={statusId} role="status"><StickerText text={note} /></div>
        {enabled && snapshot?.otherApprovalPending && <button type="button" className="text-button" onClick={onOpenWaitingApproval}>확인이 필요한 대화 열기</button>}
        <form className={`composer ${attachments.length || canStop || canSteer || voiceBusy || snapshot?.canCancelPreparation ? '' : 'composer--compact'}`} aria-label="메시지 작성" onSubmit={event => {
          event.preventDefault()
          if (sendReady) onSubmit('send')
        }}>
          {attachments.length > 0 && <div className="attachments" aria-label="첨부 파일">
            {attachments.map(attachment => <span className="attachment" key={attachment.id} title={withoutEmoji(attachment.name)}>
              <Icon name="file" /><span><StickerText text={attachment.name} /></span>
              <button type="button" className="icon-button icon-button--compact" aria-label={`${attachment.name} 빼기`}
                disabled={!enabled || pending} onClick={() => onRemoveAttachment(attachment.id)}><Icon name="close" /></button>
            </span>)}
          </div>}
          <div className="editor">
            <div className="editor-size" aria-hidden="true">{draft}{'\n'}</div>
            <textarea aria-label="메시지" aria-describedby={note ? statusId : undefined} aria-invalid={tooLong || undefined}
              placeholder="메시지를 입력하세요" dir="auto" rows={1} disabled={!enabled || voiceBusy} value={draft}
              onChange={event => onDraft(event.currentTarget.value)} onKeyDown={event => {
                if (event.key === 'Enter' && (event.ctrlKey || event.metaKey) && !event.nativeEvent.isComposing &&
                  event.nativeEvent.keyCode !== 229 && !event.repeat) {
                  event.preventDefault()
                  if (sendReady) onSubmit('send')
                }
              }} />
          </div>
          <div className="composer-tools">
            <div className="composer-actions">
              {!voiceBusy && <button type="button" className="icon-button attach-button" disabled={!enabled} aria-label="더하기 메뉴" title="더하기 메뉴" aria-haspopup="dialog" onClick={() => setSheetOpen(true)}><Icon name="plus" /></button>}
              {snapshot?.voiceDraftEpoch !== undefined && <button type="button" className="icon-button voice-button"
                disabled={!enabled || pending || tooLong || voice === 'permission' || voice === 'finishing'}
                aria-label={voiceLabel} title={voiceLabel} aria-pressed={voice === 'listening'} onClick={() => { setSheetOpen(false); onVoice() }}>
                <Icon name="microphone" />
              </button>}
              {!voiceBusy && onOpenTalk && <button type="button" className="icon-button voice-button"
                disabled={!enabled || pending} aria-label="음성 대화" title="음성 대화"
                onClick={() => { setSheetOpen(false); onOpenTalk() }}><Icon name="activity" /></button>}
              {(voice === 'listening' || voice === 'finishing') && <button type="button" className="icon-button"
                aria-label="음성 입력 취소" title="음성 입력 취소" onClick={onCancelVoice}><Icon name="close" /></button>}
            </div>
            <div className="composer-actions">
              {enabled && snapshot?.canCancelPreparation === true && <button type="button" className="text-button" onClick={onCancelPreparation}>준비 취소</button>}
              {canStop && <button type="button" className="icon-button stop-button" aria-label="현재 작업 중단" title="현재 작업 중단" onClick={onStop}><Icon name="stop" /></button>}
              {canSteer && <button type="button" className="steer-button" disabled={!sendReady || !hasText || tooLongForSteer}
                aria-label="추가 지시" title={tooLongForSteer ? '추가 지시는 4,000자까지 입력할 수 있어요.' : '실행 중인 작업에 추가 지시'} onClick={() => onSubmit('steer')}>
                <Icon name="steer" /><span>추가 지시</span>
              </button>}
              <button type="submit" className="send-button" disabled={!sendReady} title={`${sendLabel} (Ctrl / Cmd + Enter)`} aria-label={sendLabel}>
                {busy && <span>{sendLabel}</span>}<Icon name={busy ? 'plus' : 'arrow'} />
              </button>
            </div>
          </div>
        </form>
        <ComposerSheet open={sheetOpen && !voiceBusy} enabled={enabled} snapshot={snapshot}
          activeLabel={controls.active ? snapshot?.activeModelLabel : undefined}
          onAttach={onAttach} onSelectModel={onSelectModel} onClose={() => setSheetOpen(false)} />
      </div>
    </footer>
  )
}
