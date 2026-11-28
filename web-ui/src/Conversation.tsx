import { Fragment, useCallback, useEffect, useId, useLayoutEffect, useRef, useState } from 'react'
import { MotionField } from './MotionField'
import { Icon } from './Icon'
import type { IconName } from './Icon'
import { StickerText, withStickers } from './icons/Sticker'
import { RichAnswer } from './RichAnswer'
import type { BookingPaymentAction, ConversationMessage, Panel, ResultPreview, Snapshot, Task } from './bridge'
import { BookingPaymentCard } from './BookingPaymentCard'
import { isLiveTaskPhase } from './composer'
import { attachmentsBlockReply } from './bridge'
import { Character } from './character/Character'
import { useCharacter } from './character/useCharacter'
import { situationForMood, useCharacterMood } from './character/useCharacterMood'
import { COLORS, VARIANTS, type CharacterChoice, type CharacterSituation } from './character/characterData'
import { WorkCard } from './work/WorkCard'
import { WorkSheet } from './work/WorkSheet'
import { websiteWorkFor, workHistory } from './work/workHistory'
import { taskPhase, workPresentation } from './work/workPresentation'
import { QuickJobs } from './QuickJobs'

export { taskPhase }

/**
 * Empty-state suggestions (owner direction 2026-11-27): a tap sends the fixed sentence as an ordinary request. The chip
 * carries only text; the model interprets it like any typed message and any permission follows the normal flow.
 */
const roomSuggestions: { text: string; icon: IconName }[] = [
  { text: '내일 오전 일정 정리해 줘', icon: 'calendar' },
  { text: '이번 주말 서울 날씨 찾아 줘', icon: 'globe' },
  { text: '오늘 할 일 같이 정리해 줘', icon: 'check' },
]

/**
 * The other 집순이 in the room scene: different hoods and colors from the owner's choice, so the chosen one stays
 * the only one in its look. Positions, blur (depth) and float timing live in styles.css (.room-guest--N).
 */
function roomCast(host: CharacterChoice): (CharacterChoice & { size: number })[] {
  const colors = COLORS.map(item => item.id).filter(color => color !== host.color)
  const variants = VARIANTS.map(item => item.id)
  const start = variants.indexOf(host.variant) + 1
  const looks: { expr: CharacterChoice['expr']; size: number }[] = [
    { expr: 'smile', size: 64 }, { expr: 'wink', size: 56 }, { expr: 'sleepy', size: 48 }, { expr: 'surprise', size: 72 },
  ]
  return looks.map((look, index) => ({
    variant: variants[(start + index) % variants.length], color: colors[(index * 2 + 1) % colors.length], ...look,
  }))
}

function Welcome({ staff, guide, compact, single, situation, calendarGranted, hasDraft, onSendExample, onNavigate, onDismiss, onCalendar }: {
  staff?: string
  situation?: CharacterSituation
  onSendExample?(text: string): void
  guide: boolean
  compact?: boolean
  single?: boolean
  calendarGranted: boolean
  hasDraft: boolean
  onNavigate(panel: Panel): void
  onDismiss(): void
  onCalendar(): void
}) {
  const [character] = useCharacter()
  // The plain first screen only (staff rooms and the first-run guide keep their own flows).
  const suggestions = staff || guide || !onSendExample ? [] : roomSuggestions
  const quietChat = single && !guide && !compact
  return (
    <div className={`welcome${compact ? ' welcome--compact' : ''}${guide ? ' welcome--guide' : ''}`}>
      {quietChat && <div className="welcome-character"><Character choice={character} situation={situation} size={130} /></div>}
      {!compact && !quietChat && <MotionField className="room-scene">
        <span className="room-window" aria-hidden="true" /><span className="room-beam" aria-hidden="true" /><span className="room-rug" aria-hidden="true" />
        {roomCast(character).map((guest, index) =>
          <span key={index} className={`room-guest room-guest--${index + 1}`} aria-hidden="true">
            <Character choice={guest} size={guest.size} decorative />
          </span>)}
        <span className="room-host"><Character choice={character} situation={situation} size={160} /></span>
      </MotionField>}
      <h1>{staff ? `${staff}에게 말 걸기` : quietChat ? '무엇을 해 둘까요?' : !guide ? '무엇을 도와드릴까요?' : compact ? '집순이와 해볼 일' : '안녕하세요, 집순이예요!'}</h1>
      {quietChat && <p>말만 하면 끝까지 챙길게요</p>}
      {staff && <p>이 직원이 맡은 일을 확인하거나 간단한 일을 직접 부탁해 보세요.</p>}
      {!compact && suggestions.length > 0 && <div className="room-suggestions" aria-label="해볼 일 예시">
        {suggestions.map(example => <button type="button" key={example.text} className="suggestion-chip" disabled={hasDraft}
          onClick={() => onSendExample?.(example.text)}><Icon name={example.icon} />{example.text}</button>)}
      </div>}
      {guide && <>
        <p>바쁜 일을 하나씩 같이 챙겨요. 내일 일정을 정리하거나, 중요한 메일을 찾거나, 여행 숙소 가격을 비교해 드릴 수 있어요.</p>
        <section className="office-guide" aria-label="앱과 서비스 연결">
          <h2><Icon name="group" />앱과 서비스 연결</h2>
          <p>자주 쓰는 앱부터 연결해 볼까요? 필요할 때 하나씩 연결해도 좋아요.</p>
          <div className="office-guide-actions">
            {calendarGranted ? <span className="office-guide-done"><Icon name="check" />캘린더 연결됨</span> :
              <button type="button" onClick={onCalendar}><Icon name="calendar" />캘린더</button>}
            <button type="button" onClick={() => onNavigate('email')}><Icon name="mail" />이메일</button>
            <button type="button" onClick={() => onNavigate('contacts')}><Icon name="person" />연락처</button>
            <button type="button" onClick={() => onNavigate('maps')}><Icon name="globe" />위치·지도</button>
            <button type="button" className="office-guide-next" onClick={() => onNavigate('services')}>더보기<span className="sr-only"> · 전체 앱과 서비스</span></button>
          </div>
        </section>
        {onSendExample && <QuickJobs disabled={hasDraft} onChoose={onSendExample} />}
        <div className="office-guide-actions">
          <button type="button" className="office-guide-skip" onClick={onDismiss}>{compact ? '안내 닫기' : '바로 대화하기'}</button>
        </div>
      </>}
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
  const report = message.executionReport ?? []
  const hasAnswer = !!message.text.trim()
  const recentReport = report.slice(-8)
  const earlierReport = report.slice(0, -8)
  return (
    <article className={`message message--${message.role}${assistant && !startsGroup ? ' message--continued' : ''}`} aria-label={user ? '내 메시지' : assistant ? author + ' 답변' : '작업 기록'}>
      {startsGroup && <div className="message-label"><Character choice={character} size={24} decorative /> {author}</div>}
      {assistant ? <div className="assistant-text conversation-bubble">
        {message.text.trim() && <RichAnswer text={message.text} onPrepareStay={onPrepareStay} photo={onStayPhoto ? { messageId: message.id, request: onStayPhoto } : undefined} />}
        {!!report.length && <details className="execution-report" aria-label="확인한 내용" open={!hasAnswer} key={hasAnswer ? 'answered' : 'in-progress'}>
          <summary>확인한 내용</summary>
          <ol>{recentReport.map((line, index) => <li key={index}>{line}</li>)}</ol>
          {!!earlierReport.length && <details>
            <summary>앞서 확인한 단계 {earlierReport.length}개</summary>
            <ol>{earlierReport.map((line, index) => <li key={index}>{line}</li>)}</ol>
          </details>}
        </details>}
        {!!message.fileFormats?.length && <button type="button" className="attachment answer-files" disabled={!enabled}
          onClick={() => onFiles(message.id)}><Icon name="file" />
          {message.fileFormats.length === 1 ? message.fileFormats[0].toUpperCase() : `파일 ${message.fileFormats.length}개`} 열기·저장
        </button>}
        {previews.map(preview => <ResultFigure key={preview.kind + preview.src} preview={preview} />)}
      </div> : <div className="message-text" dir="auto"><StickerText text={message.text} /></div>}
      {(assistant || user) && !!message.text.trim() && (
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

export function Conversation({ snapshot, connected, enabled, onCopy, onFiles, onMessageMenu, onStopGoalWait, onNavigate, onDismissOfficeGuide, onOfficeCalendar, hasDraft, onDraftExample, historyOpen, onHistoryChange, onStayPhoto, onChooseReply, onReopenLogin, onWriteOwnReply, onSendExample, onGoalAnswer, onBookingPayment, onPdfEmail, onSpeak }: {
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
  onReopenLogin?(runId: string): void
  onGoalAnswer?(runId: string, text: string): boolean
  onPdfEmail?(messageId: string): void
  /** "직접 입력" next to reply choices: only moves focus to the composer; nothing is sent. */
  onWriteOwnReply?(): void
  /** Empty-state suggestion: sends that fixed sentence as an ordinary request (the model interprets it as usual). */
  onSendExample?(text: string): void
  onBookingPayment?(action: BookingPaymentAction, id: string): boolean
  historyOpen?: boolean
  onHistoryChange?(open: boolean): void
}) {
  const view = useRef<HTMLElement>(null)
  // The question a choice was sent for, and which choice: every chip locks so one tap sends one answer.
  const [sentChoice, setSentChoice] = useState<string | null>(null)
  const [goalReply, setGoalReply] = useState<{ runId: string; text: string } | null>(null)
  const dispatchedChoice = useRef<string | null>(null)
  const [chosenIndex, setChosenIndex] = useState<number | null>(null)
  const pinnedToEnd = useRef(true)
  const [scrolledUp, setScrolledUp] = useState(false)
  const [openWorkId, setOpenWorkId] = useState<string | null>(null)
  const [localHistoryOpen, setLocalHistoryOpen] = useState(false)
  const panelOpen = historyOpen ?? localHistoryOpen
  const setHistoryOpen = onHistoryChange ?? setLocalHistoryOpen
  const mood = useCharacterMood(snapshot)
  const messages = snapshot?.ready ? snapshot.messages.filter(item => item.text.trim() || item.executionReport?.length) : []
  const website = snapshot?.websiteCard
  const approval = snapshot?.ready ? snapshot.approvalCard : undefined
  const payment = snapshot?.ready ? snapshot.bookingPayment : undefined
  const tasks = snapshot?.ready ? snapshot.tasks.filter(task => task.id !== website?.runId) : []
  const liveTasks = tasks.filter(task => isLiveTaskPhase(task.phase) || task.phase === 'queued')
  const waitingInput = tasks.find(task => task.phase === 'waiting_input')
  const replyBlocked = attachmentsBlockReply(snapshot)
  useEffect(() => {
    if (!sentChoice) return
    const timer = window.setTimeout(() => {
      dispatchedChoice.current = null
      setSentChoice(null)
    }, 8_000)
    return () => window.clearTimeout(timer)
  }, [sentChoice])
  const empty = messages.length === 0 && tasks.length === 0 && !website && !approval && !payment
  const guide = enabled && snapshot?.showOfficeGuide === true
  const showGuideInTranscript = guide && !snapshot?.inlineLogin && !website?.inputHeight && !approval && !payment
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
      task.phase === 'waiting_input') continue
    const peers = cardsByMessage.get(task.messageId) ?? []
    peers.push(task)
    cardsByMessage.set(task.messageId, peers)
  }
  const placed = new Set([...cardsByMessage.values()].flatMap(cards => cards.map(card => card.id)))
  const unplacedTasks = tasks.filter(task => !placed.has(task.id) && task.phase !== 'waiting_input' &&
    task.phase !== 'clarification_record')
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
    if (!empty && pinnedToEnd.current) scrollToEnd()
  }, [empty, scrollToEnd, showGuideInTranscript, snapshot?.messages, snapshot?.tasks, website?.id, website?.inputHeight, approval?.id])

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
      if (!empty && pane.clientHeight > 0 && pinnedToEnd.current) scrollToEnd()
    })
    observer.observe(pane)
    return () => observer.disconnect()
  }, [empty, scrollToEnd])

  return (
    <div className="conversation-wrap">
      <div className="sr-only" role="status" aria-live="polite">{spoken}</div>
      <main className="conversation" ref={view} aria-label="대화" tabIndex={0} onScroll={event => {
        const pane = event.currentTarget
        if (empty || !pane.clientHeight) return
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
          <Welcome staff={staff} guide={guide} single={snapshot?.singleConversation} situation={situationForMood(mood)} onSendExample={onSendExample}
            calendarGranted={snapshot?.officeCalendarReadGranted === true} hasDraft={hasDraft}
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
                  {item.pdfEmail && <section className="booking-payment-card" aria-label="PDF 첨부 메일">
                    <strong>{item.pdfEmail.subject}</strong>
                    <p>받는 사람 {item.pdfEmail.to}</p>
                    <details><summary>본문과 첨부 PDF {item.pdfEmail.files.length}개 확인</summary>
                      <p style={{ whiteSpace: 'pre-wrap' }}>{item.pdfEmail.body}</p>
                      <ul>{item.pdfEmail.files.map(name => <li key={name}>{name}</li>)}</ul>
                    </details>
                    <button type="button" disabled={!enabled || !onPdfEmail} onClick={() => onPdfEmail?.(item.id)}>
                      PDF를 첨부해 Gmail에서 열기
                    </button>
                  </section>}
                  {cardsByMessage.get(item.id)?.map(task => task.phase === 'clarification_record'
                    ? <div key={task.id} className="conversation-date">추가 질문이 있었어요</div>
                    : <WorkCard key={task.id} task={task} onOpen={openWorkHistory} />)}
                  {snapshot?.tasks.filter(task => task.messageId === item.id && task.canReopenLogin).map(task =>
                    <section key={task.id} className="booking-payment-card site-login-retry" aria-label="사이트 로그인">
                      <button type="button" disabled={!enabled || !onReopenLogin}
                        onClick={() => onReopenLogin?.(task.id)}>사이트 로그인 다시 열기</button>
                    </section>)}
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
              <div role="status"><StickerText text={waitingInput.detail || waitingInput.label} /></div>
              {replyBlocked && <div>답변을 보내려면 작성창의 첨부를 먼저 취소하거나 제거해 주세요.</div>}
              {waitingInput.goalOwnerReply && onGoalAnswer && <form className="reply-options" onSubmit={event => {
                event.preventDefault()
                if (!replyBlocked && goalReply?.runId === waitingInput.id && onGoalAnswer(waitingInput.id, goalReply.text.trim())) setSentChoice(waitingInput.id)
              }}>
                <input aria-label="이어갈 작업에 답변" placeholder="이 작업에 답변" maxLength={4000}
                  value={goalReply?.runId === waitingInput.id ? goalReply.text : ''}
                  disabled={!enabled || sentChoice === waitingInput.id}
                  onChange={event => setGoalReply({ runId: waitingInput.id, text: event.target.value })} />
                <button type="submit" disabled={!enabled || replyBlocked || sentChoice === waitingInput.id ||
                  goalReply?.runId !== waitingInput.id || !goalReply.text.trim()}>답변하고 계속</button>
              </form>}
              {waitingInput.choices && onChooseReply && <div className="reply-options reply-chips" role="group" aria-label="답변 선택지">
                {/* Labels come from the model for this exact question; the recommended one is the ink pill. */}
                {waitingInput.choices.map((choice, index) => {
                  const chosen = sentChoice === waitingInput.id && chosenIndex === index
                  return <button key={choice.label} type="button"
                    className={`reply-chip${choice.recommended ? ' reply-chip--recommended' : ''}${chosen ? ' is-chosen' : ''}`}
                    aria-label={choice.recommended ? `${choice.label}, 추천` : undefined} aria-pressed={chosen}
                    disabled={!enabled || hasDraft || replyBlocked || sentChoice === waitingInput.id}
                    onClick={() => {
                      if (dispatchedChoice.current === waitingInput.id || sentChoice === waitingInput.id) return
                      if (onChooseReply(waitingInput.id, index)) {
                        dispatchedChoice.current = waitingInput.id
                        setSentChoice(waitingInput.id)
                        setChosenIndex(index)
                      }
                    }}>
                    {withStickers(choice.label)}{choice.recommended && <span className="reply-recommended" aria-hidden="true">추천</span>}
                  </button>
                })}
                {onWriteOwnReply && <button type="button" className="reply-chip reply-chip--own"
                  disabled={!enabled || sentChoice === waitingInput.id} onClick={onWriteOwnReply}>
                  <Icon name="edit" />직접 입력
                </button>}
              </div>}
            </div>}
            {showGuideInTranscript && <Welcome compact guide
              onSendExample={onSendExample} calendarGranted={snapshot?.officeCalendarReadGranted === true} hasDraft={hasDraft}
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
