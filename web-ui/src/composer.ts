import { runTarget, sameConversation } from './bridge'
import type { Snapshot } from './bridge'

export function isLiveTaskPhase(phase: string) {
  return phase === 'running' || phase === 'waiting_resource' || phase === 'approval' || phase === 'waiting_approval'
}

export function runControls(snapshot: Snapshot | null) {
  const target = runTarget(snapshot)
  const task = target && snapshot?.busy
    ? snapshot.tasks.find(item => item.id === target.runId && isLiveTaskPhase(item.phase))
    : undefined
  return {
    active: task !== undefined,
    stop: task?.canCancel ? target : null,
    steer: task && (snapshot?.canSteer ?? (task.canCancel && task.phase === 'running')) ? target : null,
  }
}

interface PendingSubmission {
  kind: 'send' | 'steer'
  text: string
  draftRevision: number
  snapshot: Snapshot
}

export interface ChatState {
  snapshot: Snapshot | null
  draft: string
  draftRevision: number
  draftDirty: boolean
  pending: PendingSubmission | null
}

export const initialChat: ChatState = { snapshot: null, draft: '', draftRevision: 0, draftDirty: false, pending: null }

type Update =
  | { type: 'snapshot'; value: Snapshot }
  | { type: 'edit'; scope: Snapshot; text: string }
  | { type: 'submit'; scope: Snapshot; text: string; kind: PendingSubmission['kind'] }
  | { type: 'release'; pending: PendingSubmission }

export function updateChat(state: ChatState, update: Update): ChatState {
  switch (update.type) {
    case 'snapshot': {
      const next = update.value
      if (!sameConversation(state.snapshot, next)) {
        return { ...initialChat, snapshot: next, draft: next.ready ? next.draft : '' }
      }
      if (!next.ready) return { ...state, snapshot: next }

      const pending = state.pending
      // Acknowledgment may arrive coalesced with a cleared draft before Android echoes the edit.
      const acknowledged = pending !== null && next.composerEpoch !== undefined &&
        next.composerEpoch > (pending.snapshot.composerEpoch ?? -1)
      const legacyReply = pending !== null && next.composerEpoch === undefined &&
        (next.busy !== pending.snapshot.busy || next.status !== pending.snapshot.status ||
          next.messages.at(-1)?.id !== pending.snapshot.messages.at(-1)?.id)
      const editedAfterSend = pending !== null && state.draftRevision !== pending.draftRevision
      const useHostDraft = next.draft === state.draft || (!state.draftDirty && !editedAfterSend) ||
        (acknowledged && pending.kind === 'send' && !editedAfterSend && state.draft === pending.text && next.draft === '')

      return {
        ...state,
        snapshot: next,
        draft: useHostDraft ? next.draft : state.draft,
        draftDirty: !useHostDraft,
        pending: acknowledged || legacyReply ? null : pending,
      }
    }
    case 'edit':
      return sameConversation(state.snapshot, update.scope) && state.draft !== update.text
        ? { ...state, draft: update.text, draftDirty: true, draftRevision: state.draftRevision + 1 }
        : state
    case 'submit':
      return state.snapshot?.ready && sameConversation(state.snapshot, update.scope)
        ? { ...state, pending: { kind: update.kind, text: update.text, draftRevision: state.draftRevision, snapshot: update.scope } }
        : state
    case 'release':
      return state.pending === update.pending ? { ...state, pending: null } : state
  }
}
