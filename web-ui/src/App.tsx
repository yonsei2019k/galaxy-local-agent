import { useEffect, useReducer, useRef, useState } from 'react'
import { SNAPSHOT_EVENT, characterChange, hasNativeBridge, postIntent, readSnapshot, type Snapshot } from './bridge'
import { initialChat, runControls, updateChat } from './composer'
import { Conversation } from './Conversation'
import { Collaboration } from './Collaboration'
import { BottomNavigation, Composer, Header, MobileSidebar, Sidebar } from './Workspace'
import { OfficeProfileForm } from './OfficeProfile'

import { VoiceTalk } from './VoiceTalk'
import { NativeCharacterSetting } from './character/useCharacter'

type Intent = Parameters<typeof postIntent>[1]

export default function App() {
  const [{ snapshot, draft, draftRevision, pending }, dispatch] = useReducer(updateChat, initialChat)
  const [connected, setConnected] = useState(hasNativeBridge)
  const [mobileOpen, setMobileOpen] = useState(false)
  const [connectionError, setConnectionError] = useState(false)
  const [officeProfileOpen, setOfficeProfileOpen] = useState(false)
  const [workHistoryScope, setWorkHistoryScope] = useState<string | null>(null)
  const [collaborationScope, setCollaborationScope] = useState<string | null>(null)
  const observedOpenRequest = useRef(0)
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
    function closeOnDesktop(event: MediaQueryListEvent) { if (event.matches) setMobileOpen(false) }
    desktop.addEventListener('change', closeOnDesktop)
    return () => {
      desktop.removeEventListener('change', closeOnDesktop)
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

  function editDraft(text: string) {
    if (!snapshot?.ready) return
    dispatch({ type: 'edit', scope: snapshot, text })
    request({ action: 'draft', payload: { text } })
  }

  function closeMobile() { setMobileOpen(false) }

  const enabled = connected && snapshot?.ready === true
  const conversationKey = JSON.stringify([snapshot?.threadId, snapshot?.branchId])
  const collaborationOpen = snapshot?.singleConversation !== true && collaborationScope === conversationKey
  const attachmentKey = JSON.stringify(snapshot?.attachments?.map(attachment => attachment.id) ?? [])
  useEffect(() => {
    const attempt = sendAttempt.current
    if (attempt && (attempt.conversation !== conversationKey || attempt.text !== draft || attempt.draftRevision !== draftRevision ||
      attempt.attachments !== attachmentKey)) sendAttempt.current = null
  }, [conversationKey, draft, draftRevision, attachmentKey])

  useEffect(() => {
    setCollaborationScope(current => current === conversationKey ? current : null)
    setWorkHistoryScope(current => current === conversationKey ? current : null)
  }, [conversationKey])

  const openRequest = snapshot?.collab?.openRequest
  useEffect(() => {
    if (snapshot?.singleConversation !== true && enabled && openRequest !== undefined && openRequest > observedOpenRequest.current) {
      observedOpenRequest.current = openRequest
      setCollaborationScope(conversationKey)
      setMobileOpen(false)
    }
  }, [conversationKey, enabled, openRequest, snapshot?.singleConversation])

  function sidebarRequest(intent: Intent) {
    if (request(intent)) {
      setCollaborationScope(intent.action === 'navigate' && intent.payload.panel === 'collab' ? conversationKey : null)
      closeMobile()
    }
  }

  const connectionNote = !connected ? 'Android 앱에서 대화를 열어 주세요.' : connectionError
    ? '앱에 연결하지 못했습니다. 입력을 보존하고 있습니다.' : ''
  const sidebarProps: Parameters<typeof Sidebar>[0] = {
    snapshot,
    enabled,
    collaborationOpen,
    onSelect: id => sidebarRequest({ action: 'selectThread', payload: { id } }),
    onOpenOffice: () => sidebarRequest({ action: 'openOffice' }),
    onNavigate: panel => sidebarRequest({ action: 'navigate', payload: { panel } }),
  }
  const openMenu = () => setMobileOpen(true)

  const appearance = snapshot?.character
  function setAppearance(choice: NonNullable<Snapshot['character']>) {
    const patch = appearance && characterChange(appearance, choice)
    if (patch) request({ action: 'setCharacter', payload: patch })
  }

  return (
    <NativeCharacterSetting.Provider value={appearance ? [appearance, setAppearance] : null}>
    <div className="workspace">
      <aside className="desktop-sidebar"><Sidebar {...sidebarProps} /></aside>
      <MobileSidebar open={mobileOpen} onClose={closeMobile}><Sidebar {...sidebarProps} onClose={closeMobile} /></MobileSidebar>
      <div className="main-column" hidden={collaborationOpen}>
        <Header key={conversationKey} snapshot={snapshot} enabled={enabled} onMenu={openMenu} onManage={() => request({ action: 'manageThreads' })}
          onRename={title => request({ action: 'renameThread', payload: { title } })}
          onOpenWork={() => setWorkHistoryScope(conversationKey)}
          onOfficeProfile={() => setOfficeProfileOpen(open => !open)} />
        {officeProfileOpen && snapshot?.officeSelected === true && <OfficeProfileForm key={`office-profile:${conversationKey}`} initial={snapshot.officeProfile ?? {}} enabled={enabled}
          onSave={entries => request({ action: 'saveOfficeProfile', payload: { entries } })}
          onClear={() => request({ action: 'clearOfficeProfile' })} onClose={() => setOfficeProfileOpen(false)} />}
        <Conversation key={`conversation:${conversationKey}`} snapshot={snapshot} connected={connected} enabled={enabled}
          historyOpen={workHistoryScope === conversationKey}
          onHistoryChange={open => setWorkHistoryScope(open ? conversationKey : null)}
          onCopy={messageId => request({ action: 'copy', payload: { messageId } })}
          onPdfEmail={messageId => request({ action: 'openPdfEmail', payload: { messageId } })}
          onFiles={messageId => request({ action: 'messageFiles', payload: { messageId } })}
          onStayPhoto={(messageId, index, kind) => request({ action: 'stayPhoto', payload: { messageId, index, ...(kind ? {kind} : {}) } })}
          onStopGoalWait={runId => request({ action: 'stopGoalWait', payload: { runId } })}
          onSpeak={messageId => request({ action: 'toggleAnswerSpeech', payload: { messageId } })}
          onMessageMenu={messageId => request({ action: 'messageMenu', payload: { messageId } })}
          onNavigate={panel => sidebarRequest({action:'navigate',payload:{panel}})}
          onDismissOfficeGuide={() => request({action:'dismissOfficeGuide'})}
          onOfficeCalendar={() => request({action:'requestOfficeCalendar'})}
          onGoalAnswer={(runId, text) => !pending && request({ action: 'answerGoalQuestion', payload: { runId, text } })}
          onChooseReply={(runId, index) => !pending && !draft.trim() &&
            request({ action: 'chooseReply', payload: { runId, index } })}
          onBookingPayment={(action, id) => request({ action, payload: { id } })}
          hasDraft={!!draft.trim() || !enabled || !!pending || !!snapshot?.voiceTalk || snapshot?.inlineLogin === true} onDraftExample={(text, needsFile) => {
            if (!enabled || pending || draft.trim() || snapshot?.inlineLogin || snapshot?.voiceTalk) return
            editDraft(text)
            if (needsFile && !snapshot?.attachments?.length) request({ action: 'attach' })
          }} />
        {snapshot?.browserLoginStatus && <div className="browser-login-status" role="status">{snapshot.browserLoginStatus}</div>}
        {snapshot?.voiceTalk && snapshot.inlineLogin !== true && <VoiceTalk talk={snapshot.voiceTalk}
          onListen={() => request({ action: 'listenVoiceTalk' })} onSend={() => request({ action: 'sendVoiceTalk' })}
          onClose={() => request({ action: 'closeVoiceTalk' })} />}
        {snapshot?.inlineLogin !== true && !snapshot?.voiceTalk && <Composer key={`composer:${conversationKey}`} snapshot={snapshot} enabled={enabled} draft={draft} pending={pending !== null} connectionNote={connectionNote}
          onDraft={editDraft} onSubmit={action => {
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
          }} onAttach={() => request({ action: 'attach' })}
          onSelectModel={id => request({ action: 'selectModel', payload: { id } })} onStop={() => {
            const target = runControls(snapshot).stop
            if (target) request({ action: 'stop', payload: target })
          }} onRemoveAttachment={id => request({ action: 'removeAttachment', payload: { id } })}
          onCancelPreparation={() => request({ action: 'cancelPreparation' })}
          onOpenWaitingApproval={() => request({ action: 'openWaitingApproval' })}
          onVoice={() => request({ action: snapshot?.voicePrompt === 'listening' ? 'finishVoicePrompt' : 'startVoicePrompt' })}
          onCancelVoice={() => request({ action: 'cancelVoicePrompt' })}
          onOpenTalk={snapshot?.voiceDraftEpoch !== undefined && !snapshot?.websiteCard?.inputHeight && !snapshot?.approvalCard
            ? () => request({ action: 'openVoiceTalk' }) : undefined} />}
        {snapshot?.singleConversation && !snapshot.inlineLogin &&
          <BottomNavigation enabled={enabled} onNavigate={panel => sidebarRequest({ action: 'navigate', payload: { panel } })} />}
      </div>
      {snapshot?.singleConversation !== true && <Collaboration key={`collab:${conversationKey}`} open={collaborationOpen} enabled={enabled} room={snapshot?.collab}
        onBack={() => { setCollaborationScope(null); request({action:'navigate',payload:{panel:'chat'}}) }} onMenu={openMenu}
        onRefresh={bindingEpoch => request({ action: 'collabRefresh', payload: { bindingEpoch } })}
        onSend={payload => request({ action: 'collabSend', payload })} />}
    </div>
    </NativeCharacterSetting.Provider>
  )
}
