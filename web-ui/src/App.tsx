import { useEffect, useReducer, useRef, useState } from 'react'
import { SNAPSHOT_EVENT, hasNativeBridge, postIntent, readSnapshot } from './bridge'
import { initialChat, runControls, updateChat } from './composer'
import { Conversation } from './Conversation'
import { Collaboration } from './Collaboration'
import { Composer, Header, MobileSidebar, Sidebar } from './Workspace'
import { OfficeProfileForm } from './OfficeProfile'

type Intent = Parameters<typeof postIntent>[1]

export default function App() {
  const [{ snapshot, draft, draftRevision, pending }, dispatch] = useReducer(updateChat, initialChat)
  const [connected, setConnected] = useState(hasNativeBridge)
  const [mobileOpen, setMobileOpen] = useState(false)
  const [connectionError, setConnectionError] = useState(false)
  const [officeProfileOpen, setOfficeProfileOpen] = useState(false)
  const [collaborationScope, setCollaborationScope] = useState<string | null>(null)
  const observedOpenRequest = useRef(0)
  const [systemDark, setSystemDark] = useState(() => window.matchMedia('(prefers-color-scheme: dark)').matches)
  const sendAttempt = useRef<{ conversation: string; attachments: string; text: string; draftRevision: number; token: string } | null>(null)

  useEffect(() => {
    function receive(event: Event) {
      if (!(event instanceof CustomEvent) || !hasNativeBridge()) return
      const snapshot = readSnapshot(event.detail)
      if (snapshot) {
        dispatch({ type: 'snapshot', value: snapshot })
        setConnected(true)
        setConnectionError(false)
      }
    }

    function requestState() {
      const available = hasNativeBridge()
      setConnected(available)
      setConnectionError(available && !postIntent(null, { action: 'ready' }))
    }

    function resume() {
      if (document.visibilityState === 'visible') requestState()
    }

    window.addEventListener(SNAPSHOT_EVENT, receive)
    document.addEventListener('visibilitychange', resume)
    requestState()
    return () => {
      window.removeEventListener(SNAPSHOT_EVENT, receive)
      document.removeEventListener('visibilitychange', resume)
    }
  }, [])

  useEffect(() => {
    if (!pending) return
    const timeout = window.setTimeout(() => dispatch({ type: 'release', pending }), 10_000)
    return () => window.clearTimeout(timeout)
  }, [pending])

  useEffect(() => {
    const desktop = window.matchMedia('(min-width: 940px)')
    const dark = window.matchMedia('(prefers-color-scheme: dark)')
    function closeOnDesktop(event: MediaQueryListEvent) { if (event.matches) setMobileOpen(false) }
    function updateSystemTheme(event: MediaQueryListEvent) { setSystemDark(event.matches) }
    desktop.addEventListener('change', closeOnDesktop)
    dark.addEventListener('change', updateSystemTheme)
    return () => {
      desktop.removeEventListener('change', closeOnDesktop)
      dark.removeEventListener('change', updateSystemTheme)
    }
  }, [])

  function request(intent: Intent) {
    const delivered = postIntent(snapshot, intent)
    if (!delivered) {
      setConnectionError(true)
      setConnected(hasNativeBridge())
    }
    return delivered
  }

  function closeMobile() { setMobileOpen(false) }

  const enabled = connected && snapshot?.ready === true
  const conversationKey = JSON.stringify([snapshot?.threadId, snapshot?.branchId])
  const collaborationOpen = collaborationScope === conversationKey
  const attachmentKey = JSON.stringify(snapshot?.attachments?.map(attachment => attachment.id) ?? [])
  useEffect(() => {
    const attempt = sendAttempt.current
    if (attempt && (attempt.conversation !== conversationKey || attempt.text !== draft || attempt.draftRevision !== draftRevision ||
      attempt.attachments !== attachmentKey)) sendAttempt.current = null
  }, [conversationKey, draft, draftRevision, attachmentKey])

  useEffect(() => {
    setCollaborationScope(current => current === conversationKey ? current : null)
  }, [conversationKey])

  const openRequest = snapshot?.collab?.openRequest
  useEffect(() => {
    if (enabled && openRequest !== undefined && openRequest > observedOpenRequest.current) {
      observedOpenRequest.current = openRequest
      setCollaborationScope(conversationKey)
      setMobileOpen(false)
    }
  }, [conversationKey, enabled, openRequest])

  function sidebarRequest(intent: Intent) {
    if (request(intent)) {
      setCollaborationScope(intent.action === 'navigate' && intent.payload.panel === 'collab' ? conversationKey : null)
      closeMobile()
    }
  }

  const status = !connected ? 'Android 앱에서 대화를 열어 주세요.' : connectionError
    ? '앱에 연결하지 못했습니다. 입력을 보존하고 있습니다.' : snapshot?.status ?? ''
  const theme = snapshot?.theme === 'dark' || (snapshot?.theme !== 'light' && systemDark) ? 'dark' : 'light'
  const sidebarProps: Parameters<typeof Sidebar>[0] = {
    snapshot,
    enabled,
    collaborationOpen,
    onSelect: id => sidebarRequest({ action: 'selectThread', payload: { id } }),
    onOpenOffice: () => sidebarRequest({ action: 'openOffice' }),
    onNavigate: panel => sidebarRequest({ action: 'navigate', payload: { panel } }),
  }
  const openMenu = () => setMobileOpen(true)

  return (
    <div className="workspace" data-theme={theme}>
      <aside className="desktop-sidebar"><Sidebar {...sidebarProps} /></aside>
      <MobileSidebar open={mobileOpen} onClose={closeMobile}><Sidebar {...sidebarProps} onClose={closeMobile} /></MobileSidebar>
      <div className="main-column" hidden={collaborationOpen}>
        <Header key={conversationKey} snapshot={snapshot} enabled={enabled} onMenu={openMenu} onManage={() => request({ action: 'manageThreads' })}
          onRename={title => request({ action: 'renameThread', payload: { title } })}
          onOfficeProfile={() => setOfficeProfileOpen(open => !open)}
          onSelectModel={id => request({ action: 'selectModel', payload: { id } })} />
        {officeProfileOpen && snapshot?.officeSelected === true && <OfficeProfileForm key={`office-profile:${conversationKey}`} initial={snapshot.officeProfile ?? {}} enabled={enabled}
          onSave={entries => request({ action: 'saveOfficeProfile', payload: { entries } })}
          onClear={() => request({ action: 'clearOfficeProfile' })} onClose={() => setOfficeProfileOpen(false)} />}
        <Conversation key={`conversation:${conversationKey}`} snapshot={snapshot} connected={connected} enabled={enabled}
          onCopy={messageId => request({ action: 'copy', payload: { messageId } })}
          onMessageMenu={messageId => request({ action: 'messageMenu', payload: { messageId } })}
          onNavigate={panel => sidebarRequest({action:'navigate',payload:{panel}})}
          onDismissOfficeGuide={() => request({action:'dismissOfficeGuide'})}
          onOfficeCalendar={() => request({action:'requestOfficeCalendar'})} />
        <Composer key={`composer:${conversationKey}`} snapshot={snapshot} enabled={enabled} draft={draft} pending={pending !== null} status={status}
          onDraft={text => {
            if (!snapshot?.ready) return
            dispatch({ type: 'edit', scope: snapshot, text })
            request({ action: 'draft', payload: { text } })
          }} onSubmit={action => {
            if (!snapshot?.ready || pending) return
            if (action === 'steer') {
              const target = runControls(snapshot).steer
              if (!target || snapshot.attachments?.length || !request({ action, payload: { ...target, text: draft } })) return
            } else {
              const attempt = sendAttempt.current
              const sameAttempt = attempt?.text === draft && attempt.draftRevision === draftRevision &&
                attempt.conversation === conversationKey && attempt.attachments === attachmentKey
              const submitToken = sameAttempt ? attempt.token : crypto.randomUUID()
              sendAttempt.current = { text: draft, conversation: conversationKey, attachments: attachmentKey, draftRevision, token: submitToken }
              if (!request({ action, payload: { text: draft, submitToken } })) return
            }
            dispatch({ type: 'submit', scope: snapshot, text: draft, kind: action })
          }} onAttach={() => request({ action: 'attach' })} onStop={() => {
            const target = runControls(snapshot).stop
            if (target) request({ action: 'stop', payload: target })
          }} onRemoveAttachment={id => request({ action: 'removeAttachment', payload: { id } })}
          onCancelPreparation={() => request({ action: 'cancelPreparation' })}
          onOpenWaitingApproval={() => request({ action: 'openWaitingApproval' })} />
      </div>
      <Collaboration key={`collab:${conversationKey}`} open={collaborationOpen} enabled={enabled} room={snapshot?.collab}
        onBack={() => { setCollaborationScope(null); request({action:'navigate',payload:{panel:'chat'}}) }} onMenu={openMenu}
        onRefresh={bindingEpoch => request({ action: 'collabRefresh', payload: { bindingEpoch } })}
        onSend={payload => request({ action: 'collabSend', payload })} />
    </div>
  )
}
