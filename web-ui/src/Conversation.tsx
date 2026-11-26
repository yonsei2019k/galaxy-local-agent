import { Fragment, useCallback, useEffect, useId, useLayoutEffect, useRef, useState } from 'react'
import type { ReactNode } from 'react'
import { useVisibleMotion } from './motion'
import { Icon } from './Icon'
import type { IconName } from './Icon'
import { Illustration } from './icons/Illustration'
import { withStickers } from './icons/Sticker'
import { RichAnswer } from './RichAnswer'
import type { BookingPaymentAction, ConversationMessage, Panel, ResultPreview, Snapshot, Task } from './bridge'
import { BookingPaymentCard } from './BookingPaymentCard'
import { isLiveTaskPhase } from './composer'
import { Character } from './character/Character'
import { useCharacter } from './character/useCharacter'
import { WorkCard } from './work/WorkCard'
import { WorkSheet } from './work/WorkSheet'
import { websiteWorkFor, workHistory } from './work/workHistory'
import { taskPhase, workPresentation } from './work/workPresentation'

export { taskPhase }

export function BrandMark({ className }: { className?: string }) {
  return <span className={`brand-mark ${className ?? ''}`} aria-hidden="true" />
}

/** A wrapper whose decorative animations pause while it is off screen (and never run under reduced motion). */
function MotionField({ className, children }: { className: string; children: ReactNode }) {
  const { ref, reduced, visible } = useVisibleMotion<HTMLDivElement>()
  return <div ref={ref} className={className} data-motion={reduced ? 'off' : 'on'} data-paused={!visible}>{children}</div>
}

const exampleRequests: { label: string; text: string; icon: IconName; needsCalendar?: boolean; needsFile?: boolean }[] = [
  { label: '오늘 일정 확인', text: '오늘 남은 일정을 시간순으로 알려줘.', icon: 'calendar', needsCalendar: true },
  { label: '웹에서 정보 찾기', text: '이번 주말 서울 날씨를 찾아서 출처와 함께 알려줘.', icon: 'globe' },
  { label: '첨부한 자료 요약', text: '첨부한 자료에서 중요한 내용만 정리해줘.', icon: 'file', needsFile: true },
]

function Welcome({ staff, guide, compact, calendarGranted, hasDraft, onDraftExample, onNavigate, onDismiss, onCalendar }: {
  staff?: string
  guide: boolean
  compact?: boolean
  calendarGranted: boolean
  hasDraft: boolean
  onDraftExample(text: string, needsFile?: boolean): void
  onNavigate(panel: Panel): void
  onDismiss(): void
  onCalendar(): void
}) {
  const [character] = useCharacter()
  const [step, setStep] = useState(0)
  return (
    <div className={compact ? "welcome welcome--compact" : "welcome"}>
      {!compact && <MotionField className="mascot-field">
        <Character choice={character} size={160} />
        <span className="twinkle twinkle--1" aria-hidden="true" /><span className="twinkle twinkle--2" aria-hidden="true" />
        <span className="twinkle twinkle--3" aria-hidden="true" />
      </MotionField>}
      <h1>{staff ? `${staff}에게 말 걸기` : guide ? '안녕하세요, 집순이예요!' : '무엇을 도와드릴까요?'}</h1>
      {staff && <p>이 직원이 맡은 일을 확인하거나 간단한 일을 직접 부탁해 보세요.</p>}
      {guide && <section className="office-guide" aria-label="집순이 처음 안내">
        <div className="office-guide-steps" aria-hidden="true">{[0, 1, 2].map(index =>
          <span key={index} className={index === step ? 'is-current' : undefined} />)}</div>
        {step === 1 && <MotionField className="office-guide-art-wrap"><Illustration name="connections" className="office-guide-art art-float" /></MotionField>}
        <div aria-live="polite">
          {step === 0 && <p>일정 확인부터 웹 검색, 파일 정리까지 여기서 부탁해 주세요. 어떤 일부터 해볼까요?</p>}
          {step === 1 && <p>일정을 보려면 휴대폰 일정 읽기를 허용해 주세요. Google이나 컴퓨터는 필요할 때 연결해도 돼요.</p>}
          {step === 2 && <p>해볼 일을 고르면 입력창에 문장을 써드릴게요. 고쳐 쓰거나 바로 보내도 돼요. 휴대폰 일정이 연결되지 않았으면 먼저 읽기 권한을 물어볼게요.</p>}
        </div>
        <div className={`office-guide-actions${step === 2 ? ' office-guide-actions--examples' : ''}`}>
          {step === 0 && <button type="button" className="office-guide-next" onClick={() => setStep(1)}>필요한 설정 보기</button>}
          {step === 1 && <>
            {calendarGranted ? <span role="status" className="office-guide-done"><Icon name="check" />휴대폰 일정 읽기 연결됨</span> :
              <button type="button" onClick={onCalendar}>휴대폰 일정 읽기 허용</button>}
            <button type="button" onClick={() => onNavigate('connections')}>앱과 컴퓨터 연결</button>
            <button type="button" className="office-guide-next" onClick={() => setStep(2)}>해볼 일 고르기</button>
          </>}
          {step === 2 && exampleRequests.map(example =>
            <button type="button" key={example.label} className="example-chip" disabled={hasDraft} onClick={() => {
              onDraftExample(example.text, example.needsFile)
              if (example.needsCalendar && !calendarGranted) onCalendar()
            }}><Icon name={example.icon} />{example.label}</button>
          )}
          <button type="button" className="office-guide-skip" onClick={onDismiss}>바로 대화하기</button>
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
        onClick={() => setOpen(value => !value)}><Icon name="more" /></button>
      {open && <div className="answer-menu-list" role="menu" id={menuId} aria-label="답변 동작">
        <button type="button" role="menuitem" ref={first} onClick={() => { close(true); onCopy(messageId) }}>복사</button>
        <button type="button" role="menuitem" onClick={() => { close(true); onMessageMenu(messageId) }}>더 보기</button>
      </div>}
    </div>
  )
}

const conversationDate = new Intl.DateTimeFormat('ko-KR', { dateStyle: 'full' })

function dateLabel(message?: ConversationMessage) {
  return message?.sentAt === undefined ? null : conversationDate.format(message.sentAt)
}

function Message({ message, previous, previousHasCards, previews, enabled, onCopy, onFiles, onMessageMenu, onPrepareStay, onStayPhoto, onSpeak, speaking }: {
  message: ConversationMessage
  previous?: ConversationMessage
  previousHasCards: boolean
  previews: ResultPreview[]
  enabled: boolean
  onCopy(messageId: string): void
  onFiles(messageId: string): void
  onMessageMenu(messageId: string): void
  onSpeak?: (messageId: string) => void
  speaking?: boolean
  onPrepareStay?: (text: string) => void
  onStayPhoto?: (messageId: string, index: number, kind?: 'map') => boolean
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
        <RichAnswer text={message.text} onPrepareStay={onPrepareStay} photo={onStayPhoto ? { messageId: message.id, request: onStayPhoto } : undefined} />
        {!!message.fileFormats?.length && <button type="button" className="attachment answer-files" disabled={!enabled}
          onClick={() => onFiles(message.id)}><Icon name="file" />
          {message.fileFormats.length === 1 ? message.fileFormats[0].toUpperCase() : `파일 ${message.fileFormats.length}개`} 열기·저장
        </button>}
        {previews.map(preview => <ResultFigure key={preview.kind + preview.src} preview={preview} />)}
      </div> : <div className="message-text" dir="auto">{message.text}</div>}
      {(assistant || user) && (
        <div className="message-actions">
          {assistant
            ? <>
                {onSpeak && <button type="button" className="icon-button icon-button--compact"
                  aria-label={speaking ? '답변 읽기 중지' : '답변 읽어주기'}
                  onClick={() => onSpeak(message.id)}><Icon name={speaking ? 'stop' : 'speaker'} /></button>}
                <AnswerMenu messageId={message.id} enabled={enabled} onCopy={onCopy} onMessageMenu={onMessageMenu} />
              </>
            : <button type="button" className="icon-button icon-button--compact" disabled={!enabled}
              aria-label="메시지 메뉴" onClick={() => onMessageMenu(message.id)}><Icon name="more" /></button>}
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

const endedPhases = new Set(['completed', 'failed', 'cancelled', 'interrupted'])

export function Conversation({ snapshot, connected, enabled, onCopy, onFiles, onMessageMenu, onStopGoalWait, onNavigate, onDismissOfficeGuide, onOfficeCalendar, hasDraft, onDraftExample, historyOpen, onHistoryChange, onStayPhoto, onChooseReply, onBookingPayment, onSpeak }: {
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
  onDraftExample(text: string, needsFile?: boolean): void
  onStayPhoto?: (messageId: string, index: number, kind?: 'map') => boolean
  onSpeak?(messageId: string): void
  onChooseReply?(runId: string, index: number): boolean
  onBookingPayment?(action: BookingPaymentAction, id: string): boolean
  historyOpen?: boolean
  onHistoryChange?(open: boolean): void
}) {
  const view = useRef<HTMLElement>(null)
  const [sentChoice, setSentChoice] = useState<string | null>(null)
  const pinnedToEnd = useRef(true)
  const [scrolledUp, setScrolledUp] = useState(false)
  const [openWorkId, setOpenWorkId] = useState<string | null>(null)
  const [localHistoryOpen, setLocalHistoryOpen] = useState(false)
  const panelOpen = historyOpen ?? localHistoryOpen
  const setHistoryOpen = onHistoryChange ?? setLocalHistoryOpen
  const messages = snapshot?.ready ? snapshot.messages.filter(item => item.text.trim()) : []
  const website = snapshot?.websiteCard
  const approval = snapshot?.ready ? snapshot.approvalCard : undefined
  const payment = snapshot?.ready ? snapshot.bookingPayment : undefined
  const tasks = snapshot?.ready ? snapshot.tasks.filter(task => task.id !== website?.runId) : []
  const liveTasks = tasks.filter(task => isLiveTaskPhase(task.phase) || task.phase === 'queued')
  const waitingInput = tasks.find(task => task.phase === 'waiting_input')
  useEffect(() => {
    if (!sentChoice) return
    const timer = window.setTimeout(() => setSentChoice(null), 8_000)
    return () => window.clearTimeout(timer)
  }, [sentChoice])
  const empty = messages.length === 0 && tasks.length === 0 && !website && !approval && !payment
  const guide = enabled && snapshot?.showOfficeGuide === true
  const activeTask = liveTasks.find(task => task.id === snapshot?.activeRunId) ?? liveTasks[0]
  const visibleUserIds = new Set(messages.filter(item => item.role === 'user').map(item => item.id))
  const websiteAnchor = website?.messageId && visibleUserIds.has(website.messageId) ? website.messageId : null
  const approvalAnchor = approval?.messageId && visibleUserIds.has(approval.messageId) ? approval.messageId : null
  const paymentAnchor = payment?.messageId && visibleUserIds.has(payment.messageId) ? payment.messageId : null
  const paymentCard = payment && <BookingPaymentCard payment={payment} enabled={enabled} onAction={onBookingPayment} />
  const approvalCard = approval && <article className="task-card approval-input-slot"
    data-native-approval-slot={approval.id} aria-label="작업 확인이 필요해요" />
  const websiteWork = websiteWorkFor(snapshot)
  const openWorkHistory = () => { setOpenWorkId(null); setHistoryOpen(true) }
  const websiteCard = website && (website.inputHeight ?
    <article className="task-card website-input-slot" data-native-website-slot={website.id}
      style={{height:website.inputHeight}} aria-label={website.title} /> :
    websiteWork && <section id="website-work" className="website-work-card" aria-label={website.title}>
      <WorkCard task={websiteWork} onOpen={openWorkHistory} />
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
  const lastClarification = tasks.filter(task => task.phase === 'clarification_record').at(-1)
  const unplacedTasks = tasks.filter(task => !placed.has(task.id) && task.phase !== 'waiting_input' &&
    (task.phase !== 'clarification_record' || task === lastClarification))
  const history = panelOpen ? workHistory(snapshot) : undefined
  const sheetTask = history && openWorkId !== null
    ? [...history.active, ...history.recent].find(task => task.id === openWorkId) ?? null : null
  const announcement = website?.working || (website && !website.inputHeight)
    ? [website.title, website.status].filter(Boolean).join(': ')
    : activeTask ? [activeTask.label, activeTask.detail].filter(Boolean).join(': ') : ''
  const loading = connected && !snapshot?.ready
  const staff = snapshot?.singleConversation ? undefined : snapshot?.staffRooms?.find(room => room.id === snapshot.threadId)?.title

  useEffect(() => { if (openWorkId !== null && !sheetTask) setOpenWorkId(null) }, [openWorkId, sheetTask])

  // A task that was seen working and now ended is announced once, on top of the live announcement.
  const seenWorking = useRef(new Set<string>())
  const [ended, setEnded] = useState<{ text: string; over: string } | null>(null)
  useEffect(() => {
    const phrases: string[] = []
    if (snapshot?.ready) {
      const present = new Set(snapshot.tasks.map(task => task.id))
      for (const id of seenWorking.current) if (!present.has(id)) seenWorking.current.delete(id)
      for (const task of snapshot.tasks) {
        if (isLiveTaskPhase(task.phase)) seenWorking.current.add(task.id)
        else if (endedPhases.has(task.phase) && seenWorking.current.delete(task.id)) phrases.push(`${task.label || '작업'} ${workPresentation(task).title}`)
      }
    }
    const text = phrases.length > 1 ? `작업 ${phrases.length}개가 끝났어요: ${phrases.join(', ')}` : phrases[0] ?? ''
    if (text) setEnded({ text, over: announcement })
    else setEnded(current => current && current.over !== announcement ? null : current)
  }, [snapshot, announcement])
  const spoken = ended && ended.over === announcement ? ended.text : announcement

  const scrollToEnd = useCallback(() => {
    if (view.current) view.current.scrollTop = view.current.scrollHeight
    pinnedToEnd.current = true
    setScrolledUp(false)
  }, [])

  useLayoutEffect(() => {
    if (pinnedToEnd.current) scrollToEnd()
  }, [scrollToEnd, snapshot?.messages, snapshot?.tasks, website?.id, website?.inputHeight, approval?.id])

  useLayoutEffect(() => {
    if (website?.inputHeight) view.current?.querySelector('[data-native-website-slot]')?.scrollIntoView?.({
      block: website.keyboardOpen ? 'start' : 'nearest',
    })
  }, [website?.id, website?.inputHeight, website?.keyboardOpen])

  useLayoutEffect(() => {
    if (approval?.id) view.current?.querySelector('[data-native-approval-slot]')?.scrollIntoView?.({ block: 'nearest' })
  }, [approval?.id])

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
      <div className="sr-only" role="status" aria-live="polite">{spoken}</div>
      <main className="conversation" ref={view} aria-label="대화" tabIndex={0} onScroll={event => {
        const pane = event.currentTarget
        if (!pane.clientHeight) return
        const pinned = pane.scrollHeight - pane.clientHeight - pane.scrollTop < Math.min(96, pane.clientHeight / 3)
        pinnedToEnd.current = pinned
        setScrolledUp(!pinned)
      }}>
        {loading ? <div className="loading" role="status">
          <div className="skeleton" aria-hidden="true">
            <span className="skeleton-bubble skeleton-bubble--mine" />
            <span className="skeleton-line" /><span className="skeleton-line skeleton-line--wide" /><span className="skeleton-line skeleton-line--short" />
          </div>
          <span className="loading-label"><Icon name="clock" /> 대화를 불러오는 중</span>
        </div> : empty ?
          <Welcome staff={staff} guide={guide}
            calendarGranted={snapshot?.officeCalendarReadGranted === true} hasDraft={hasDraft} onDraftExample={onDraftExample}
            onNavigate={onNavigate} onDismiss={onDismissOfficeGuide} onCalendar={onOfficeCalendar} /> : (
          <div className="transcript">
            <div className="messages" role="log" aria-label="메시지">
              {messages.map((item, index) => {
                const date = dateLabel(item)
                const newDay = date !== null && date !== dateLabel(messages[index - 1])
                return <Fragment key={item.id}>
                  {newDay && <div className="conversation-date">{date}</div>}
                  <Message message={item} previous={messages[index - 1]}
                    previousHasCards={newDay || (index > 0 && (
                      !!cardsByMessage.get(messages[index - 1].id)?.length ||
                      websiteAnchor === messages[index - 1].id || approvalAnchor === messages[index - 1].id ||
                      paymentAnchor === messages[index - 1].id))}
                    previews={snapshot?.resultPreviews?.filter(preview => preview.messageId === item.id) ?? []}
                    speaking={snapshot?.spokenMessageId === item.id}
                    onSpeak={enabled && !snapshot?.voiceTalk && !snapshot?.voicePrompt && !snapshot?.inlineLogin &&
                      !snapshot?.websiteCard?.inputHeight && !snapshot?.approvalCard && snapshot?.voiceDraftEpoch !== undefined &&
                      (!snapshot?.busy || snapshot?.spokenMessageId === item.id) ? onSpeak : undefined}
                    enabled={enabled} onCopy={onCopy} onFiles={onFiles} onMessageMenu={onMessageMenu}
                    onPrepareStay={enabled && !hasDraft ? onDraftExample : undefined} onStayPhoto={enabled ? onStayPhoto : undefined} />
                  {cardsByMessage.get(item.id)?.map(task => <WorkCard key={task.id} task={task} onOpen={openWorkHistory} />)}
                  {websiteAnchor === item.id && websiteCard}
                  {approvalAnchor === item.id && approvalCard}
                  {paymentAnchor === item.id && paymentCard}
                </Fragment>
              })}
              {!websiteAnchor && websiteCard}
              {!approvalAnchor && approvalCard}
              {!paymentAnchor && paymentCard}
            </div>
            {unplacedTasks.length > 0 && <section className="work-cards" aria-label="작업">
              {unplacedTasks.map(task => <WorkCard key={task.id} task={task} onOpen={openWorkHistory} />)}
            </section>}
            {waitingInput && !liveTasks.length && <div className="reply-needed">
              <div role="status">{waitingInput.detail || waitingInput.label}</div>
              {waitingInput.choices && onChooseReply && <div className="reply-options" role="group" aria-label="답변 선택지">
                {waitingInput.choices.map((choice, index) => <button key={choice.label} type="button"
                  disabled={!enabled || hasDraft || !!snapshot?.attachments?.length || sentChoice === waitingInput.id}
                  onClick={() => {
                    if (onChooseReply(waitingInput.id, index)) setSentChoice(waitingInput.id)
                  }}>
                  {withStickers(choice.label)}{choice.recommended && <span className="reply-recommended"><Icon name="sparkle" />추천</span>}
                </button>)}
              </div>}
            </div>}
            {guide && !liveTasks.length && !snapshot?.busy && <Welcome compact guide
              calendarGranted={snapshot?.officeCalendarReadGranted === true} hasDraft={hasDraft} onDraftExample={onDraftExample}
              onNavigate={onNavigate} onDismiss={onDismissOfficeGuide} onCalendar={onOfficeCalendar} />}
          </div>
        )}
      </main>
      <WorkSheet task={sheetTask} history={history} enabled={enabled}
        steps={sheetTask && sheetTask.id === websiteWork?.id ? website?.steps : undefined}
        onSelect={setOpenWorkId} onBack={() => setOpenWorkId(null)}
        onClose={() => { setOpenWorkId(null); setHistoryOpen(false) }} onStopGoalWait={onStopGoalWait} />
      {scrolledUp && !empty && (
        <button type="button" className="icon-button scroll-latest" aria-label="최근 메시지로 이동" title="최근 메시지로 이동" onClick={scrollToEnd}>
          <Icon name="arrowDown" />
        </button>
      )}
    </div>
  )
}
