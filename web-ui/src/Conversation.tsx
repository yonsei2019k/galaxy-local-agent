import { useCallback, useId, useLayoutEffect, useRef, useState } from 'react'
import { Icon } from './Icon'
import type { IconName } from './Icon'
import type { ConversationMessage, Snapshot, Task } from './bridge'
import { isLiveTaskPhase } from './composer'

const phasePresentation: Record<string, { title: string; tone: string; icon: IconName }> = {
  queued: { title: '대기 중', tone: 'neutral', icon: 'clock' },
  running: { title: '진행 중', tone: 'active', icon: 'activity' },
  waiting_resource: { title: '작업 순서 기다리는 중', tone: 'neutral', icon: 'clock' },
  approval: { title: '승인 필요', tone: 'review', icon: 'shield' },
  waiting_approval: { title: '승인 필요', tone: 'review', icon: 'shield' },
  paused: { title: '일시 정지', tone: 'review', icon: 'pause' },
  failed: { title: '실패', tone: 'failed', icon: 'alert' },
  completed: { title: '응답 종료', tone: 'complete', icon: 'check' },
  cancelled: { title: '중단됨', tone: 'neutral', icon: 'stop' },
  interrupted: { title: '재시작으로 중단됨', tone: 'neutral', icon: 'stop' },
}

const unknownPhase = { title: '작업 상태', tone: 'neutral', icon: 'clock' } as const

export function taskPhase(task: Task) {
  return Object.hasOwn(phasePresentation, task.phase) ? phasePresentation[task.phase] : unknownPhase
}

export function BrandMark({ className }: { className?: string }) {
  return <span className={`brand-mark ${className ?? ''}`}><Icon name="robot" /></span>
}

function Welcome() {
  const [mascotAvailable, setMascotAvailable] = useState(true)
  return (
    <div className="welcome">
      <div className="mascot-field" aria-hidden="true">
        {mascotAvailable
          ? <img className="mascot" src={`${import.meta.env.BASE_URL}ziphap-mascot.png`} alt="" onError={() => setMascotAvailable(false)} />
          : <BrandMark className="mascot-fallback" />}
      </div>
      <h1>무엇을 도와드릴까요?</h1>
    </div>
  )
}

function Message({ message, enabled, onCopy, onMessageMenu }: {
  message: ConversationMessage
  enabled: boolean
  onCopy(messageId: string): void
  onMessageMenu(messageId: string): void
}) {
  const user = message.role === 'user'
  const assistant = message.role === 'assistant'
  return (
    <article className={`message message--${message.role}`} aria-label={user ? '내 메시지' : assistant ? 'ZipHap 답변' : '작업 기록'}>
      {assistant && <div className="message-label"><BrandMark className="brand-mark--small" /> ZipHap</div>}
      {!user && !assistant && <div className="message-label">작업 기록</div>}
      <div className="message-text" dir="auto">{message.text}</div>
      {(assistant || user) && (
        <div className="message-actions">
          {assistant && <button type="button" className="icon-button icon-button--compact" disabled={!enabled}
            aria-label="답변 복사" title="답변 복사" onClick={() => onCopy(message.id)}><Icon name="copy" /></button>}
          <button type="button" className="icon-button icon-button--compact" disabled={!enabled}
            aria-label={assistant ? '답변 메뉴' : '메시지 메뉴'} onClick={() => onMessageMenu(message.id)}>···</button>
        </div>
      )}
    </article>
  )
}

function TaskCard({ task }: { task: Task }) {
  const phase = taskPhase(task)
  const heading = useId()
  return (
    <article className={`task-card task-card--${phase.tone}`} aria-labelledby={heading}>
      <div className="task-heading">
        <span className="task-icon"><Icon name={phase.icon} /></span>
        <div><p className="task-state">{phase.title}</p><h2 id={heading}>{task.label || '작업'}</h2></div>
      </div>
      {task.detail && <p className="task-detail" dir="auto">{task.detail}</p>}
      {task.evidence && task.evidence.length > 0 && (
        <details className="task-evidence">
          <summary><Icon name="file" /> 작업 내역 <Icon name="down" className="details-chevron" /></summary>
          <ul>{task.evidence.map(item => (
            <li key={item.id}><div className="evidence-label">{item.label}</div>{item.detail && <p dir="auto">{item.detail}</p>}</li>
          ))}</ul>
        </details>
      )}
    </article>
  )
}

export function Conversation({ snapshot, connected, enabled, onCopy, onMessageMenu }: {
  snapshot: Snapshot | null
  connected: boolean
  enabled: boolean
  onCopy(messageId: string): void
  onMessageMenu(messageId: string): void
}) {
  const view = useRef<HTMLElement>(null)
  const pinnedToEnd = useRef(true)
  const [scrolledUp, setScrolledUp] = useState(false)
  const messages = snapshot?.ready ? snapshot.messages.filter(item => item.text.trim()) : []
  const tasks = snapshot?.ready ? snapshot.tasks : []
  const liveTasks = tasks.filter(task => isLiveTaskPhase(task.phase) || task.phase === 'queued')
  const pausedTasks = tasks.filter(task => task.phase === 'paused')
  const previousTasks = tasks.filter(task => task.phase !== 'paused' && task.phase !== 'queued' && !isLiveTaskPhase(task.phase))
  const empty = messages.length === 0 && tasks.length === 0
  const loading = connected && !snapshot?.ready

  const scrollToEnd = useCallback(() => {
    if (view.current) view.current.scrollTop = view.current.scrollHeight
    pinnedToEnd.current = true
    setScrolledUp(false)
  }, [])

  useLayoutEffect(() => {
    if (pinnedToEnd.current) scrollToEnd()
  }, [scrollToEnd, snapshot?.messages, snapshot?.tasks])

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
      <main className="conversation" ref={view} aria-label="대화" tabIndex={0} onScroll={event => {
        const pane = event.currentTarget
        if (!pane.clientHeight) return
        const pinned = pane.scrollHeight - pane.clientHeight - pane.scrollTop < Math.min(96, pane.clientHeight / 3)
        pinnedToEnd.current = pinned
        setScrolledUp(!pinned)
      }}>
        {loading ? <div className="loading" role="status"><Icon name="clock" /> 대화를 불러오는 중</div> : empty ? <Welcome /> : (
          <div className="transcript">
            {previousTasks.length > 0 && <details className="task-evidence">
              <summary><Icon name="history" /> 이전 작업 기록 <Icon name="down" className="details-chevron" /></summary>
              <div className="tasks">
                {previousTasks.map(item => <TaskCard key={item.id} task={item} />)}
              </div>
            </details>}
            {pausedTasks.length > 0 && <section className="tasks" aria-label="일시 정지된 작업">
              {pausedTasks.map(item => <TaskCard key={item.id} task={item} />)}
            </section>}
            <div className="messages" role="log" aria-label="메시지">
              {messages.map(item => <Message key={item.id} message={item} enabled={enabled} onCopy={onCopy} onMessageMenu={onMessageMenu} />)}
            </div>
            {liveTasks.length > 0 && <section className="tasks" aria-label="현재 작업 상태">
              {liveTasks.map(item => <TaskCard key={item.id} task={item} />)}
            </section>}
          </div>
        )}
      </main>
      {scrolledUp && !empty && (
        <button type="button" className="icon-button scroll-latest" aria-label="최근 메시지로 이동" title="최근 메시지로 이동" onClick={scrollToEnd}>
          <Icon name="arrowDown" />
        </button>
      )}
    </div>
  )
}
