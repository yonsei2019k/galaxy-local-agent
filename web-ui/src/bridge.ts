export interface ResultPreview {
  messageId: string
  kind: 'image' | 'chart'
  src: string | null
}

export interface ConversationMessage {
  id: string
  role: 'user' | 'assistant' | 'system' | 'tool'
  text: string
  fileFormats?: string[]
  authorLabel?: string
}

interface Thread {
  id: string
  title: string
  selected: boolean
  working?: boolean
}

interface Evidence {
  id: string
  label: string
  detail: string
}

export interface Task {
  id: string
  phase: string
  label: string
  detail: string
  canCancel: boolean
  messageId?: string
  evidence?: Evidence[]
}

interface Model {
  id: string
  label: string
}

interface Attachment {
  id: string
  name: string
}

export interface Snapshot {
  threadId: string
  branchId: string
  ready: boolean
  draft: string
  busy: boolean
  status: string
  modelId: string
  modelLabel: string
  messages: ConversationMessage[]
  resultPreviews?: ResultPreview[]
  threads: Thread[]
  staffRooms?: Thread[]
  officeSelected?: boolean
  showOfficeGuide?: boolean
  officeCalendarReadGranted?: boolean
  officeProfile?: OfficeEntries
  tasks: Task[]
  models: Model[]
  activeModelLabel?: string
  activeRunId?: string | null
  actionEpoch?: number
  canSteer?: boolean
  attachments?: Attachment[]
  canCancelPreparation?: boolean
  otherApprovalPending?: boolean
  composerEpoch?: number
  singleConversation?: boolean
  inlineLogin?: boolean
  browserLoginStatus?: string
  voicePrompt?: 'permission' | 'listening' | 'finishing'
  voiceDraftEpoch?: number
  theme?: 'light' | 'dark' | 'system'
  collab?: CollaborationSnapshot
}

export type Recipient = 'user' | 'codex' | 'claude' | 'all'

export interface CollaborationMessage {
  sequence: number
  messageId: string
  clientMessageId?: string | null
  sender: Exclude<Recipient, 'all'>
  recipient: string
  text: string
  createdAt: string
  dataOnly: true
  executionAuthority: false
}

interface CollaborationReceipt {
  clientMessageId: string
  bindingEpoch: number
  accepted: boolean
  epoch?: number
}

export interface CollaborationSnapshot {
  bindingEpoch: number
  available: boolean
  loading: boolean
  status: string
  cursor: number
  messages: CollaborationMessage[]
  sendReceipt?: CollaborationReceipt
  openRequest?: number
}

export type OfficeEntries = Record<string, { birthDate?: string; schoolGrade?: string }>

const OFFICE_SEAT_KEYS = ['CHAIR', 'DIRECTOR', 'ASSOCIATE_GYUMIN', 'ASSOCIATE_SEOYUL']

export function officeEntries(value: unknown): value is OfficeEntries {
  return record(value) && Object.keys(value).length <= 4 && Object.entries(value).every(([seat, entry]) =>
    OFFICE_SEAT_KEYS.includes(seat) && record(entry) && Object.keys(entry).every(key => key === 'birthDate' || key === 'schoolGrade') &&
    (entry.birthDate === undefined || (typeof entry.birthDate === 'string' && entry.birthDate.length <= 10)) &&
    (entry.schoolGrade === undefined || (typeof entry.schoolGrade === 'string' && entry.schoolGrade.length <= 20)))
}

export const MAX_COLLAB_TEXT = 8_000

export type Panel = 'chat' | 'contacts' | 'settings' | 'images' | 'tools' | 'projects' | 'history' | 'historySearch' | 'collab' | 'capabilities' | 'connections' | 'privacy'

type Intent =
  | { action: 'draft'; payload: { text: string } }
  | { action: 'copy' | 'messageFiles' | 'messageMenu'; payload: { messageId: string } }
  | { action: 'send'; payload: { text: string; submitToken: string } }
  | { action: 'stop'; payload: RunTarget }
  | { action: 'steer'; payload: RunTarget & { text: string } }
  | { action: 'collabSend'; payload: { bindingEpoch: number; clientMessageId: string; text: string; recipient: Recipient } }
  | { action: 'collabRefresh'; payload: { bindingEpoch: number } }
  | { action: 'selectThread' | 'selectModel' | 'removeAttachment'; payload: { id: string } }
  | { action: 'renameThread'; payload: { title: string } }
  | { action: 'navigate'; payload: { panel: Panel } }
  | { action: 'saveOfficeProfile'; payload: { entries: OfficeEntries } }
  | { action: 'clearOfficeProfile' }
  | { action: 'startVoicePrompt' | 'finishVoicePrompt' | 'cancelVoicePrompt' }
  | { action: 'openOffice' | 'dismissOfficeGuide' | 'requestOfficeCalendar' | 'attach' | 'manageThreads' | 'cancelPreparation' | 'openWaitingApproval' | 'ready' }

interface RunTarget {
  runId: string
  actionEpoch: number
}

declare global {
  interface Window {
    ziphapNative?: { postMessage(message: string): void }
  }
}

export const SNAPSHOT_EVENT = 'ziphap:snapshot'

function record(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function strings(value: Record<string, unknown>, ...keys: string[]) {
  return keys.every(key => typeof value[key] === 'string')
}

function list<T>(value: unknown, guard: (item: unknown) => item is T): value is T[] {
  return Array.isArray(value) && value.every(guard)
}

function resultPreview(value: unknown): value is ResultPreview {
  return record(value) && typeof value.messageId === 'string' &&
    (value.kind === 'image' || value.kind === 'chart') &&
    (value.src === null || (typeof value.src === 'string' && value.src.length <= 240024 &&
      /^data:image\/jpeg;base64,[A-Za-z0-9+/]+={0,2}$/.test(value.src)))
}

function message(value: unknown): value is ConversationMessage {
  return record(value) && strings(value, 'id', 'text') &&
    (value.role === 'user' || value.role === 'assistant' || value.role === 'system' || value.role === 'tool') &&
    (value.fileFormats === undefined || (value.role === 'assistant' && Array.isArray(value.fileFormats) &&
      value.fileFormats.length <= 16 && value.fileFormats.every(format =>
        typeof format === 'string' && ['pdf', 'docx', 'xlsx', 'txt', 'csv'].includes(format)))) &&
    (value.authorLabel === undefined || (value.role === 'assistant' && typeof value.authorLabel === 'string' &&
      value.authorLabel.trim().length > 0 && value.authorLabel.length <= 80 &&
      !/[\u0000-\u001f\u007f]/.test(value.authorLabel)))
}

function thread(value: unknown): value is Thread {
  return record(value) && strings(value, 'id', 'title') && typeof value.selected === 'boolean' &&
    (value.working === undefined || typeof value.working === 'boolean')
}

function evidence(value: unknown): value is Evidence {
  return record(value) && strings(value, 'id', 'label', 'detail')
}

function task(value: unknown): value is Task {
  return record(value) && strings(value, 'id', 'phase', 'label', 'detail') &&
    typeof value.canCancel === 'boolean' && (value.messageId === undefined || typeof value.messageId === 'string') && (value.evidence === undefined || list(value.evidence, evidence))
}

function model(value: unknown): value is Model {
  return record(value) && strings(value, 'id', 'label')
}

function attachment(value: unknown): value is Attachment {
  return record(value) && strings(value, 'id', 'name')
}

export function isRecipient(value: unknown): value is Recipient {
  return value === 'user' || value === 'codex' || value === 'claude' || value === 'all'
}

function collaborationMessage(value: unknown): value is CollaborationMessage {
  return record(value) && epoch(value.sequence) && strings(value, 'messageId', 'recipient', 'text', 'createdAt') &&
    value.messageId !== '' && isRecipient(value.sender) && value.sender !== 'all' &&
    value.dataOnly === true && value.executionAuthority === false &&
    (value.clientMessageId === undefined || value.clientMessageId === null || typeof value.clientMessageId === 'string')
}

function collaboration(value: unknown): CollaborationSnapshot | undefined {
  if (!record(value) || !epoch(value.bindingEpoch) || typeof value.available !== 'boolean' || typeof value.loading !== 'boolean' ||
    typeof value.status !== 'string' || !epoch(value.cursor) || !Array.isArray(value.messages)) return undefined
  const rawReceipt = value.sendReceipt
  const sendReceipt = record(rawReceipt) && rawReceipt.bindingEpoch === value.bindingEpoch &&
    typeof rawReceipt.clientMessageId === 'string' && typeof rawReceipt.accepted === 'boolean'
    ? { clientMessageId: rawReceipt.clientMessageId, bindingEpoch: value.bindingEpoch, accepted: rawReceipt.accepted,
        epoch: epoch(rawReceipt.epoch) ? rawReceipt.epoch : undefined }
    : undefined
  return {
    bindingEpoch: value.bindingEpoch, available: value.available, loading: value.loading, status: value.status, cursor: value.cursor,
    messages: value.messages.filter(collaborationMessage), sendReceipt,
    openRequest: epoch(value.openRequest) ? value.openRequest : undefined,
  }
}

export function readSnapshot(value: unknown): Snapshot | null {
  if (!record(value) || !strings(value, 'threadId', 'branchId', 'draft', 'status', 'modelId', 'modelLabel')) return null
  if (typeof value.ready !== 'boolean' || typeof value.busy !== 'boolean') return null
  if (value.ready && (!value.threadId || !value.branchId)) return null
  if (!list(value.messages, message) || !list(value.threads, thread) || !list(value.tasks, task) || !list(value.models, model)) return null
  const threads = value.threads
  if (threads.some(item => item.selected && item.id !== value.threadId)) return null
  const messages = value.messages
  if (value.resultPreviews !== undefined && (!list(value.resultPreviews, resultPreview) ||
    value.resultPreviews.length > 2 ||
    value.resultPreviews.some(preview => !messages.some(message => message.id === preview.messageId && message.role === 'assistant')))) return null
  if (value.officeSelected !== undefined && typeof value.officeSelected !== 'boolean') return null
  if (value.showOfficeGuide !== undefined && (typeof value.showOfficeGuide !== 'boolean' ||
    (value.showOfficeGuide && value.officeSelected !== true))) return null
  if (value.officeCalendarReadGranted !== undefined && (typeof value.officeCalendarReadGranted !== 'boolean' ||
    value.officeSelected !== true)) return null
  if (value.officeProfile !== undefined && (value.officeSelected !== true || !officeEntries(value.officeProfile))) return null
  if (value.staffRooms !== undefined && (!list(value.staffRooms, thread) || value.staffRooms.length > 4 ||
    value.staffRooms.some(room => !threads.some(item => item.id === room.id && item.selected === room.selected)))) return null
  if (value.activeModelLabel !== undefined && typeof value.activeModelLabel !== 'string') return null
  if (value.activeRunId !== undefined && value.activeRunId !== null && typeof value.activeRunId !== 'string') return null
  if (value.actionEpoch !== undefined && !epoch(value.actionEpoch)) return null
  if (value.canSteer !== undefined && typeof value.canSteer !== 'boolean') return null
  if (value.canCancelPreparation !== undefined && typeof value.canCancelPreparation !== 'boolean') return null
  if (value.otherApprovalPending !== undefined && typeof value.otherApprovalPending !== 'boolean') return null
  if (value.attachments !== undefined && !list(value.attachments, attachment)) return null
  if (value.singleConversation !== undefined && typeof value.singleConversation !== 'boolean') return null
  if (value.inlineLogin !== undefined && typeof value.inlineLogin !== 'boolean') return null
  if (value.browserLoginStatus !== undefined && (typeof value.browserLoginStatus !== 'string' || value.browserLoginStatus.length > 240)) return null
  if (value.composerEpoch !== undefined && !epoch(value.composerEpoch)) return null
  if (value.voiceDraftEpoch !== undefined && !epoch(value.voiceDraftEpoch)) return null
  if (value.voicePrompt !== undefined && value.voicePrompt !== 'permission' && value.voicePrompt !== 'listening' && value.voicePrompt !== 'finishing') return null
  if (value.theme !== undefined && value.theme !== 'light' && value.theme !== 'dark' && value.theme !== 'system') return null
  return { ...(value as unknown as Snapshot), collab: collaboration(value.collab) }
}

function epoch(value: unknown): value is number {
  return typeof value === 'number' && Number.isSafeInteger(value) && value >= 0
}

export function runTarget(scope: Snapshot | null): RunTarget | null {
  if (!scope?.ready || !scope.activeRunId || scope.actionEpoch === undefined) return null
  return { runId: scope.activeRunId, actionEpoch: scope.actionEpoch }
}

export function sameConversation(a: Snapshot | null, b: Snapshot | null) {
  return a !== null && b !== null && a.threadId === b.threadId && a.branchId === b.branchId
}

export function hasNativeBridge() {
  return typeof window.ziphapNative?.postMessage === 'function'
}

/** A message hands control to Android. Android owns scope, authorization and the actual result. */
export function postIntent(scope: Snapshot | null, intent: Intent): boolean {
  if (intent.action !== 'ready' && !scope?.ready) return false
  if (intent.action === 'selectThread' && !scope?.threads.some(thread => thread.id === intent.payload.id)) return false
  if (intent.action === 'selectModel' && !scope?.models.some(model => model.id === intent.payload.id)) return false
  if ((intent.action === 'saveOfficeProfile' || intent.action === 'clearOfficeProfile' ||
    intent.action === 'dismissOfficeGuide' || intent.action === 'requestOfficeCalendar') && !scope?.officeSelected) return false
  if (intent.action === 'saveOfficeProfile' && !officeEntries(intent.payload.entries)) return false
  if (intent.action === 'copy' && !scope?.messages.some(message => message.role === 'assistant' && message.id === intent.payload.messageId)) return false
  if (intent.action === 'stop' || intent.action === 'steer') {
    const target = runTarget(scope)
    if (!target || target.runId !== intent.payload.runId || target.actionEpoch !== intent.payload.actionEpoch) return false
  }
  if (intent.action === 'collabSend' || intent.action === 'collabRefresh') {
    if (!scope?.collab || scope.collab.bindingEpoch !== intent.payload.bindingEpoch) return false
  }
  if (intent.action === 'collabSend') {
    const { clientMessageId, text, recipient } = intent.payload
    if (!scope?.collab?.available || !isRecipient(recipient) || !text.trim() || text.length > MAX_COLLAB_TEXT || text.includes('\0') ||
      !/^[\da-f]{8}-[\da-f]{4}-4[\da-f]{3}-[89ab][\da-f]{3}-[\da-f]{12}$/i.test(clientMessageId)) return false
  }
  const native = window.ziphapNative
  if (!native || typeof native.postMessage !== 'function') return false

  try {
    native.postMessage(JSON.stringify({
      version: 1,
      threadId: scope?.threadId ?? '',
      branchId: scope?.branchId ?? '',
      action: intent.action,
      payload: 'payload' in intent ? intent.payload : {},
    }))
    return true
  } catch {
    return false
  }
}
