import { useEffect, useId, useLayoutEffect, useMemo, useRef, useState } from 'react'
import { Icon } from './Icon'
import { MAX_COLLAB_TEXT, isRecipient } from './bridge'
import type { CollaborationMessage, CollaborationSnapshot, Recipient } from './bridge'

const names: Record<Recipient, string> = { user: '사용자', codex: 'Codex', claude: 'Claude', all: '모두' }
const dayAndTime = new Intl.DateTimeFormat('ko-KR', { month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit' })

interface OutgoingMessage {
  bindingEpoch: number
  clientMessageId: string
  text: string
  recipient: Recipient
}

interface Attempt extends OutgoingMessage {
  awaiting: boolean
  previousReceipt: string | undefined
}

function displayedMessages(messages: CollaborationMessage[]) {
  const ids = new Set<string>()
  const sequences = new Set<number>()
  return [...messages].sort((a, b) => a.sequence - b.sequence).filter(message => {
    if (ids.has(message.messageId) || sequences.has(message.sequence)) return false
    ids.add(message.messageId)
    sequences.add(message.sequence)
    return true
  })
}

function receiptKey(receipt: CollaborationSnapshot['sendReceipt']) {
  return receipt && `${receipt.bindingEpoch}:${receipt.clientMessageId}:${receipt.accepted}:${receipt.epoch ?? ''}`
}

function Message({ message }: { message: CollaborationMessage }) {
  const date = new Date(message.createdAt)
  const validDate = !Number.isNaN(date.getTime())
  return (
    <article className={`collab-message collab-message--${message.sender}`}>
      <div className="collab-message-meta">
        <strong>{names[message.sender]}</strong>
        <span>받는 사람 {isRecipient(message.recipient) ? names[message.recipient] : message.recipient}</span>
        {validDate && <time dateTime={message.createdAt}>{dayAndTime.format(date)}</time>}
      </div>
      <div className="message-text" dir="auto">{message.text}</div>
    </article>
  )
}

export function Collaboration({ open, enabled, room, onBack, onMenu, onRefresh, onSend }: {
  open: boolean
  enabled: boolean
  room?: CollaborationSnapshot
  onBack(): void
  onMenu(): void
  onRefresh(bindingEpoch: number): boolean
  onSend(message: OutgoingMessage): boolean
}) {
  const [text, setText] = useState('')
  const [recipient, setRecipient] = useState<Recipient>('all')
  const [attempt, setAttempt] = useState<Attempt | null>(null)
  const [notice, setNotice] = useState('')
  const feed = useRef<HTMLElement>(null)
  const heading = useRef<HTMLHeadingElement>(null)
  const pinned = useRef(true)
  const lastBinding = useRef<number | undefined>(undefined)
  const statusId = useId()
  const messages = useMemo(() => displayedMessages(room?.messages ?? []), [room?.messages])
  const bindingEpoch = room?.bindingEpoch

  useLayoutEffect(() => {
    if (bindingEpoch === undefined) return
    if (lastBinding.current !== undefined && lastBinding.current !== bindingEpoch) {
      setAttempt(null)
      setNotice('PC 연결이 바뀌었습니다. 전송할 내용을 다시 확인해 주세요.')
    }
    lastBinding.current = bindingEpoch
  }, [bindingEpoch])

  useEffect(() => {
    if (!attempt || attempt.bindingEpoch !== bindingEpoch) return
    const receipt = room?.sendReceipt
    const recorded = messages.some(message => message.sender === 'user' && message.clientMessageId === attempt.clientMessageId)
    if (recorded || (receipt?.clientMessageId === attempt.clientMessageId && receipt.accepted)) {
      if (text === attempt.text && recipient === attempt.recipient) setText('')
      setAttempt(null)
      setNotice('')
    } else if (attempt.awaiting && receipt?.clientMessageId === attempt.clientMessageId && receiptKey(receipt) !== attempt.previousReceipt) {
      setAttempt({ ...attempt, awaiting: false })
      setNotice('협업방에 저장하지 못했습니다. 작성한 내용은 유지됩니다.')
    }
  }, [attempt, bindingEpoch, messages, room?.sendReceipt, text, recipient])

  useEffect(() => {
    if (!attempt?.awaiting) return
    const timeout = window.setTimeout(() => {
      if (lastBinding.current !== attempt.bindingEpoch) return
      setAttempt(current => current === attempt ? { ...current, awaiting: false } : current)
      setNotice('저장 여부를 확인하지 못했습니다. 작성한 내용은 유지됩니다.')
    }, 12_000)
    return () => window.clearTimeout(timeout)
  }, [attempt])

  useLayoutEffect(() => {
    if (open && feed.current && pinned.current) feed.current.scrollTop = feed.current.scrollHeight
  }, [open, messages])

  useLayoutEffect(() => {
    if (open) heading.current?.focus({ preventScroll: true })
  }, [open])

  const available = enabled && room?.available === true
  const tooLong = text.length > MAX_COLLAB_TEXT
  const invalid = text.includes('\0')
  const canSend = open && available && !attempt?.awaiting && !!text.trim() && !tooLong && !invalid
  const status = tooLong ? '메시지는 8,000자까지 보낼 수 있습니다.' : invalid ? '입력에 사용할 수 없는 문자가 있습니다.'
    : notice || (attempt?.awaiting ? '협업방에 저장되었는지 확인 중입니다.' : room?.status || '')

  function send() {
    if (!canSend || bindingEpoch === undefined) return
    const retry = attempt?.bindingEpoch === bindingEpoch && attempt.text === text && attempt.recipient === recipient
    const clientMessageId = retry ? attempt.clientMessageId : crypto.randomUUID()
    const message = { bindingEpoch, clientMessageId, text, recipient }
    const sent = onSend(message)
    setAttempt({ ...message, awaiting: sent, previousReceipt: receiptKey(room?.sendReceipt) })
    setNotice(sent ? '' : '협업방에 연결하지 못했습니다. 작성한 내용은 유지됩니다.')
  }

  return (
    <div className="main-column collaboration" hidden={!open}>
      <header className="topbar">
        <div className="topbar-context">
          <button type="button" className="icon-button menu-button" aria-label="대화 및 앱 메뉴 열기" onClick={onMenu}><Icon name="menu" /></button>
          <button type="button" className="icon-button collab-back" aria-label="일반 대화로 돌아가기" title="일반 대화로 돌아가기" onClick={onBack}><Icon name="back" /></button>
          <h1 className="topbar-title" tabIndex={-1} ref={heading}>협업방</h1>
        </div>
        <button type="button" className="icon-button" disabled={!enabled || !room || room.loading} aria-label="협업방 새로고침" title="협업방 새로고침"
          onClick={() => { if (bindingEpoch !== undefined && onRefresh(bindingEpoch)) setNotice('') }}><Icon name="refresh" /></button>
      </header>
      <main className="conversation collab-feed" aria-label="협업방 메시지" ref={feed} onScroll={event => {
        const pane = event.currentTarget
        pinned.current = pane.scrollHeight - pane.clientHeight - pane.scrollTop < 96
      }}>
        {messages.length ? <div className="transcript collab-messages" role="log" aria-label="협업 메시지">
          {messages.map(message => <Message key={message.messageId} message={message} />)}
        </div> : <div className="collab-empty">
          <span className="brand-mark"><Icon name="group" /></span>
          <h2>사용자, Codex, Claude</h2>
          <p>{available && !room?.loading ? '협업 메시지가 없습니다.' : '협업방 연결을 확인해 주세요.'}</p>
        </div>}
      </main>
      <footer className="composer-footer">
        <div className="composer-width">
          <div className="collab-policy"><Icon name="shield" /> 협업 내용은 실행 승인을 대신하지 않습니다.</div>
          <div className="composer-status" id={statusId} role="status">{open && status}</div>
          <form className="composer" aria-label="협업 메시지 작성" onSubmit={event => { event.preventDefault(); send() }}>
            <div className="editor">
              <div aria-hidden="true" className="editor-size">{text}{'\n'}</div>
              <textarea rows={1} value={text} dir="auto" aria-label="협업 메시지" aria-describedby={status ? statusId : undefined}
                aria-invalid={tooLong || invalid || undefined} disabled={!available} placeholder="협업 메시지를 입력하세요"
                onChange={event => { setText(event.currentTarget.value); setNotice('') }} onKeyDown={event => {
                  if (event.key === 'Enter' && (event.ctrlKey || event.metaKey) && !event.nativeEvent.isComposing && !event.repeat) {
                    event.preventDefault()
                    send()
                  }
                }} />
            </div>
            <div className="composer-tools">
              <label className="collab-recipient">받는 사람
                <select value={recipient} disabled={!available} onChange={event => {
                  const value = event.currentTarget.value
                  if (isRecipient(value)) { setRecipient(value); setNotice('') }
                }}>
                  {Object.entries(names).map(([value, label]) => <option key={value} value={value}>{label}</option>)}
                </select>
              </label>
              <button type="submit" className="send-button" aria-label="협업 메시지 보내기" title="협업 메시지 보내기" disabled={!canSend}><Icon name="arrow" /></button>
            </div>
          </form>
        </div>
      </footer>
    </div>
  )
}
