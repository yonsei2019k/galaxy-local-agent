import { Fragment, useCallback, useId, useLayoutEffect, useRef, useState } from 'react'
import { Icon } from './Icon'
import { RichAnswer } from './RichAnswer'
import type { ConversationMessage, Panel, ResultPreview, Snapshot, Task } from './bridge'
import { isLiveTaskPhase } from './composer'
import { Character } from './character/Character'
import { useCharacter } from './character/useCharacter'
import { WorkCard } from './work/WorkCard'
import { WorkSheet } from './work/WorkSheet'
import { taskPhase } from './work/workPresentation'

export { taskPhase }

export function BrandMark({ className }: { className?: string }) {
  return <span className={`brand-mark ${className ?? ''}`} aria-hidden="true" />
}

const exampleRequests = [
  { label: '오늘 일정 확인', text: '오늘 남은 일정을 시간순으로 알려줘.', needsCalendar: true },
  { label: '웹에서 정보 찾기', text: '이번 주말 서울 날씨를 찾아서 출처와 함께 알려줘.' },
  { label: '첨부한 자료 요약', text: '첨부한 자료에서 중요한 내용만 정리해줘.' },
]

function Welcome({ staff, guide, compact, calendarGranted, hasDraft, onDraftExample, onNavigate, onDismiss, onCalendar }: {
  staff?: string
  guide: boolean
  compact?: boolean
  calendarGranted: boolean
  hasDraft: boolean
  onDraftExample(text: string): void
  onNavigate(panel: Panel): void
  onDismiss(): void
  onCalendar(): void
}) {
  const [character] = useCharacter()
  const [step, setStep] = useState(0)
  return (
    <div className={compact ? "welcome welcome--compact" : "welcome"}>
      {!compact && <div className="mascot-field"><Character choice={character} size={160} /></div>}
      <h1>{staff ? `${staff}에게 말 걸기` : guide ? '안녕하세요, 집순이예요!' : '무엇을 도와드릴까요?'}</h1>
      {staff && <p>이 직원이 맡은 일을 확인하거나 간단한 일을 직접 부탁해 보세요.</p>}
      {guide && <section className="office-guide" aria-label="집순이 처음 안내">
        <div aria-live="polite">
          {step === 0 && <p>일정 확인부터 웹 검색, 파일 정리까지 여기서 부탁해 주세요. 어떤 일부터 해볼까요?</p>}
          {step === 1 && <p>일정을 보려면 휴대폰 일정 읽기를 허용해 주세요. Google이나 컴퓨터는 필요할 때 연결해도 돼요.</p>}
          {step === 2 && <p>해볼 일을 고르면 입력창에 문장을 써드릴게요. 고쳐 쓰거나 바로 보내도 돼요. 휴대폰 일정이 연결되지 않았으면 먼저 읽기 권한을 물어볼게요.</p>}
        </div>
        <div className="office-guide-actions">
          {step === 0 && <button type="button" onClick={() => setStep(1)}>필요한 설정 보기</button>}
          {step === 1 && <>
            {calendarGranted ? <span role="status">휴대폰 일정 읽기 연결됨</span> :
              <button type="button" onClick={onCalendar}>휴대폰 일정 읽기 허용</button>}
            <button type="button" onClick={() => onNavigate('connections')}>앱과 컴퓨터 연결</button>
            <button type="button" onClick={() => setStep(2)}>해볼 일 고르기</button>
          </>}
          {step === 2 && exampleRequests.map(example =>
            <button type="button" key={example.label} disabled={hasDraft} onClick={() => {
              onDraftExample(example.text)
              if (example.needsCalendar && !calendarGranted) onCalendar()
            }}>{example.label}</button>
          )}
          <button type="button" onClick={onDismiss}>바로 대화하기</button>
        </div>
        {step === 2 && hasDraft && <p>입력창에 작성한 내용을 먼저 확인해 주세요.</p>}
      </section>}
    </div>
  )
}

function AnswerMenu({ messageId, enabled, onCopy, onMessageMenu }: {
  messageId: string
  enabled: boolean
  onCopy(messageId: string): void
  onMessageMenu(messageId: string): void
}) {
  const [open, setOpen] = useState(false)
  const root = useRef<HTMLDivElement>(null)
  const trigger = useRef<HTMLButtonElement>(null)
  const first = useRef<HTMLButtonElement>(null)
  const menuId = useId()
  const close = useCallback((restore: boolean) => {
    setOpen(false)
    if (restore) trigger.current?.focus()
  }, [])
  useLayoutEffect(() => { if (open) first.current?.focus() }, [open])
  useLayoutEffect(() => {
    if (!open) return
    const outside = (event: Event) => { if (!root.current?.contains(event.target as Node)) close(false) }
    document.addEventListener('pointerdown', outside)
    return () => document.removeEventListener('pointerdown', outside)
  }, [open, close])
  return (
    <div className="answer-menu" ref={root} onKeyDown={event => { if (event.key === 'Escape' && open) { event.stopPropagation(); close(true) } }}>
      <button type="button" ref={trigger} className="icon-button icon-button--compact" disabled={!enabled}
        aria-label="답변 메뉴" aria-haspopup="menu" aria-expanded={open} aria-controls={open ? menuId : undefined}
        onClick={() => setOpen(value => !value)}>···</button>
      {open && <div className="answer-menu-list" role="menu" id={menuId} aria-label="답변 동작">
        <button type="button" role="menuitem" ref={first} onClick={() => { close(true); onCopy(messageId) }}>복사</button>
        <button type="button" role="menuitem" onClick={() => { close(true); onMessageMenu(messageId) }}>더 보기</button>
      </div>}
    </div>
  )
}

function Message({ message, previous, previousHasCards, previews, enabled, onCopy, onFiles, onMessageMenu }: {
  message: ConversationMessage
  previous?: ConversationMessage
  previousHasCards: boolean
  previews: ResultPreview[]
  enabled: boolean
  onCopy(messageId: string): void
  onFiles(messageId: string): void
  onMessageMenu(messageId: string): void
}) {
  const [character] = useCharacter()
  const user = message.role === 'user'
  const assistant = message.role === 'assistant'
  const author = assistant ? message.authorLabel ?? '집순이' : undefined
  const startsGroup = assistant && (previousHasCards || !(previous?.role === 'assistant' && (previous.authorLabel ?? '집순이') === author))
  return (
    <article className={`message message--${message.role}${assistant && !startsGroup ? ' message--continued' : ''}`} aria-label={user ? '내 메시지' : assistant ? author + ' 답변' : '작업 기록'}>
      {startsGroup && <div className="message-label"><Character choice={character} size={24} decorative /> {author}</div>}
      {assistant ? <div className="assistant-text">
        <RichAnswer text={message.text} />
        {!!message.fileFormats?.length && <button type="button" className="attachment answer-files" disabled={!enabled}
          onClick={() => onFiles(message.id)}><Icon name="file" />
          {message.fileFormats.length === 1 ? message.fileFormats[0].toUpperCase() : `파일 ${message.fileFormats.length}개`} 열기·저장
        </button>}
        {previews.map(preview => <ResultFigure key={preview.kind + preview.src} preview={preview} />)}
      </div> : <div className="message-text" dir="auto">{message.text}</div>}
      {(assistant || user) && (
        <div className="message-actions">
          {assistant
            ? <AnswerMenu messageId={message.id} enabled={enabled} onCopy={onCopy} onMessageMenu={onMessageMenu} />
            : <button type="button" className="icon-button icon-button--compact" disabled={!enabled}
              aria-label="메시지 메뉴" onClick={() => onMessageMenu(message.id)}>···</button>}
        </div>
      )}
    </article>
  )
}

function ResultFigure({ preview }: { preview: ResultPreview }) {
  const [failed, setFailed] = useState(false)
  const label = preview.kind === 'chart' ? '데이터 분석 차트' : '기기에서 생성한 이미지'
  return <figure className="answer-result">
    <figcaption>{label}</figcaption>
    {preview.src && !failed
      ? <img src={preview.src} alt={label} onError={() => setFailed(true)} />
      : <p role="status">미리보기를 표시할 수 없습니다</p>}
  </figure>
}

export function Conversation({ snapshot, connected, enabled, onCopy, onFiles, onMessageMenu, onStopGoalWait, onNavigate, onDismissOfficeGuide, onOfficeCalendar, hasDraft, onDraftExample }: {
  snapshot: Snapshot | null
  connected: boolean
  enabled: boolean
  onCopy(messageId: string): void
  onFiles(messageId: string): void
  onMessageMenu(messageId: string): void
  onStopGoalWait(runId: string): void
  onNavigate(panel: Panel): void
  onDismissOfficeGuide(): void
  onOfficeCalendar(): void
  hasDraft: boolean
  onDraftExample(text: string): void
}) {
  const view = useRef<HTMLElement>(null)
  const pinnedToEnd = useRef(true)
  const [scrolledUp, setScrolledUp] = useState(false)
  const [openWorkId, setOpenWorkId] = useState<string | null>(null)
  const messages = snapshot?.ready ? snapshot.messages.filter(item => item.text.trim()) : []
  const website = snapshot?.websiteCard
  const tasks = snapshot?.ready ? snapshot.tasks.filter(task => task.id !== website?.runId) : []
  const liveTasks = tasks.filter(task => isLiveTaskPhase(task.phase) || task.phase === 'queued')
  const waitingInput = tasks.find(task => task.phase === 'waiting_input')
  const websiteTask = snapshot?.tasks.find(task => task.id === website?.runId)
  const empty = messages.length === 0 && tasks.length === 0 && !website
  const guide = enabled && snapshot?.showOfficeGuide === true
  const activeTask = liveTasks.find(task => task.id === snapshot?.activeRunId) ?? liveTasks[0]
  const visibleUserIds = new Set(messages.filter(item => item.role === 'user').map(item => item.id))
  const websiteAnchor = website?.messageId && visibleUserIds.has(website.messageId) ? website.messageId : null
  const websiteWork: Task | null = website && !website.inputHeight ? {
    id: website.runId ?? `website-${website.id}`,
    phase: website.working ? 'running' : websiteTask && !isLiveTaskPhase(websiteTask.phase) ? websiteTask.phase : 'website',
    label: website.title, detail: website.status, canCancel: websiteTask?.canCancel ?? false, evidence: websiteTask?.evidence,
  } : null
  const websiteCard = website && (website.inputHeight ?
    <article className="task-card website-input-slot" data-native-website-slot={website.id}
      style={{height:website.inputHeight}} aria-label={website.title} /> :
    websiteWork && <section id="website-work" className="website-work-card" aria-label={website.title}>
      <WorkCard task={websiteWork} onOpen={setOpenWorkId} />
    </section>)
  const cardsByMessage = new Map<string, Task[]>()
  for (const task of tasks) {
    if (!task.messageId || !visibleUserIds.has(task.messageId) ||
      task.phase === 'waiting_input' || task.phase === 'clarification_record') continue
    const peers = cardsByMessage.get(task.messageId) ?? []
    peers.push(task)
    cardsByMessage.set(task.messageId, peers)
  }
  const placed = new Set([...cardsByMessage.values()].flatMap(cards => cards.map(card => card.id)))
  const unplacedTasks = tasks.filter(task => !placed.has(task.id) && task.phase !== 'waiting_input')
  const sheetTask = openWorkId === null ? null : websiteWork?.id === openWorkId ? websiteWork : tasks.find(task => task.id === openWorkId) ?? null
  const loading = connected && !snapshot?.ready
  const staff = snapshot?.singleConversation ? undefined : snapshot?.staffRooms?.find(room => room.id === snapshot.threadId)?.title

  const scrollToEnd = useCallback(() => {
    if (view.current) view.current.scrollTop = view.current.scrollHeight
    pinnedToEnd.current = true
    setScrolledUp(false)
  }, [])

  useLayoutEffect(() => {
    if (pinnedToEnd.current) scrollToEnd()
  }, [scrollToEnd, snapshot?.messages, snapshot?.tasks, website?.id, website?.inputHeight])

  useLayoutEffect(() => {
    if (website?.inputHeight) view.current?.querySelector('[data-native-website-slot]')?.scrollIntoView?.({
      block: website.keyboardOpen ? 'start' : 'nearest',
    })
  }, [website?.id, website?.inputHeight, website?.keyboardOpen])

  useLayoutEffect(() => {
    const pane = view.current
    if (!pane || typeof ResizeObserver === 'undefined') return
    const observer = new ResizeObserver(() => {
      if (pane.clientHeight > 0 && pinnedToEnd.current) scrollToEnd()
    })
    observer.observe(pane)
    return () => observer.disconnect()
  }, [scrollToEnd])

  return (
    <div className="conversation-wrap">
      {snapshot?.singleConversation && (activeTask || website?.working || website?.inputHeight) && <button type="button" className="current-work"
        onClick={() => (website?.inputHeight ? document.querySelector('[data-native-website-slot]') : website?.working ?
          document.getElementById('website-work') : activeTask && document.getElementById(`work-${activeTask.id}`))?.scrollIntoView?.({ block: 'nearest' })}>
        <Icon name={activeTask ? taskPhase(activeTask).icon : 'activity'} />
        <span>{website?.working || website?.inputHeight ? website.title : activeTask && `${taskPhase(activeTask).title} · ${activeTask.label}`}</span>
        <Icon name="down" />
      </button>}
      <main className="conversation" ref={view} aria-label="대화" tabIndex={0} onScroll={event => {
        const pane = event.currentTarget
        if (!pane.clientHeight) return
        const pinned = pane.scrollHeight - pane.clientHeight - pane.scrollTop < Math.min(96, pane.clientHeight / 3)
        pinnedToEnd.current = pinned
        setScrolledUp(!pinned)
      }}>
        {loading ? <div className="loading" role="status"><Icon name="clock" /> 대화를 불러오는 중</div> : empty ?
          <Welcome staff={staff} guide={guide}
            calendarGranted={snapshot?.officeCalendarReadGranted === true} hasDraft={hasDraft} onDraftExample={onDraftExample}
            onNavigate={onNavigate} onDismiss={onDismissOfficeGuide} onCalendar={onOfficeCalendar} /> : (
          <div className="transcript">
            <div className="messages" role="log" aria-label="메시지">
              {messages.map((item, index) => <Fragment key={item.id}>
                <Message message={item} previous={messages[index - 1]}
                  previousHasCards={index > 0 && (!!cardsByMessage.get(messages[index - 1].id)?.length || websiteAnchor === messages[index - 1].id)}
                  previews={snapshot?.resultPreviews?.filter(preview => preview.messageId === item.id) ?? []}
                  enabled={enabled} onCopy={onCopy} onFiles={onFiles} onMessageMenu={onMessageMenu} />
                {cardsByMessage.get(item.id)?.map(task => <WorkCard key={task.id} task={task} onOpen={setOpenWorkId} />)}
                {websiteAnchor === item.id && websiteCard}
              </Fragment>)}
              {!websiteAnchor && websiteCard}
            </div>
            {unplacedTasks.length > 0 && <section className="work-cards" aria-label="작업">
              {unplacedTasks.map(task => <WorkCard key={task.id} task={task} onOpen={setOpenWorkId} />)}
            </section>}
            {waitingInput && !liveTasks.length && <div className="reply-needed" role="status">{waitingInput.detail || waitingInput.label}</div>}
            {guide && !liveTasks.length && !snapshot?.busy && <Welcome compact guide
              calendarGranted={snapshot?.officeCalendarReadGranted === true} hasDraft={hasDraft} onDraftExample={onDraftExample}
              onNavigate={onNavigate} onDismiss={onDismissOfficeGuide} onCalendar={onOfficeCalendar} />}
          </div>
        )}
      </main>
      <WorkSheet task={sheetTask} enabled={enabled} steps={sheetTask && sheetTask === websiteWork ? website?.steps : undefined}
        onClose={() => setOpenWorkId(null)} onStopGoalWait={onStopGoalWait} />
      {scrolledUp && !empty && (
        <button type="button" className="icon-button scroll-latest" aria-label="최근 메시지로 이동" title="최근 메시지로 이동" onClick={scrollToEnd}>
          <Icon name="arrowDown" />
        </button>
      )}
    </div>
  )
}
