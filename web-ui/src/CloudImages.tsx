import { useEffect, useRef, useState } from 'react'
import { Icon } from './Icon'
import './cloud-images.css'

export type CloudImageJobState = 'queued' | 'running' | 'cancelling' | 'unknown' | 'failed' | 'completed'

export interface CloudImagesState {
  available: boolean
  blockedReason?: string
  providerLabel: string
  prompt: string
  references: Array<{ id: string; previewUrl: string; label: string }>
  job: null | { id: string; state: CloudImageJobState; stageLabel?: string; error?: string }
  result: null | { id: string; url: string; width: number; height: number; sha256: string; model: string; downloadConfirmed: boolean }
}

export interface CloudImagesProps {
  state: CloudImagesState
  onPromptChange(text: string): void
  onPickReference(): void
  onGenerate(request: { prompt: string; referenceIds: string[] }): void
  onCancel(jobId: string): void
  onSave(resultId: string): void
  onOpenLocalImages(): void
}

const PENDING_TIMEOUT_MS = 15000
const ACTIVE: CloudImageJobState[] = ['queued', 'running', 'cancelling', 'unknown']

type Tone = 'active' | 'review' | 'failed' | 'complete' | 'neutral'

function describeJob(job: NonNullable<CloudImagesState['job']>): { label: string; detail?: string; tone: Tone; icon: 'clock' | 'activity' | 'alert' | 'check' | 'stop' } {
  switch (job.state) {
    case 'queued': return { label: '대기 중', tone: 'neutral', icon: 'clock' }
    case 'running': return { label: job.stageLabel ? `생성 중 · ${job.stageLabel}` : '생성 중', tone: 'active', icon: 'activity' }
    case 'cancelling': return { label: '취소 중', tone: 'neutral', icon: 'stop' }
    case 'unknown': return { label: '상태 확인 필요', detail: '작업 결과를 아직 확인하지 못했습니다. 완료로 간주하지 않습니다.', tone: 'review', icon: 'alert' }
    case 'failed': return { label: '실패', detail: job.error || '이미지를 만들지 못했습니다.', tone: 'failed', icon: 'alert' }
    case 'completed': return { label: '완료', tone: 'complete', icon: 'check' }
  }
}

export function CloudImages({ state, onPromptChange, onPickReference, onGenerate, onCancel, onSave, onOpenLocalImages }: CloudImagesProps) {
  const { job, result, references } = state
  const active = !!job && ACTIVE.includes(job.state)
  const [pendingJobKey, setPendingJobKey] = useState<string | null>(null)
  const jobKey = job ? `${job.id}:${job.state}` : 'none'
  const pendingFrom = useRef<string>('none')
  const submitPending = useRef(false)

  // Clear the double-submit guard once the job changes, generation becomes unavailable, or after a timeout.
  useEffect(() => {
    if (pendingJobKey !== null && jobKey !== pendingFrom.current) {
      submitPending.current = false
      setPendingJobKey(null)
    }
  }, [jobKey, pendingJobKey])
  useEffect(() => {
    if (!state.available) {
      submitPending.current = false
      setPendingJobKey(null)
    }
  }, [state.available])
  useEffect(() => {
    if (pendingJobKey === null) return
    const timer = window.setTimeout(() => {
      submitPending.current = false
      setPendingJobKey(null)
    }, PENDING_TIMEOUT_MS)
    return () => window.clearTimeout(timer)
  }, [pendingJobKey])

  const pending = pendingJobKey !== null
  const canGenerate = state.available && state.prompt.trim().length > 0 && !active && !pending
  const jobInfo = job ? describeJob(job) : null

  function generate() {
    if (!canGenerate || submitPending.current) return
    // React batches state updates: a second click can arrive before rerender.
    submitPending.current = true
    pendingFrom.current = jobKey
    setPendingJobKey(jobKey)
    onGenerate({ prompt: state.prompt.trim(), referenceIds: references.map(item => item.id) })
  }

  return (
    <section className="cloud-images" aria-label="클라우드 이미지">
      <div className="cloud-images-scroll">
        <header className="cloud-images-header">
          <div className="cloud-images-heading">
            <h2 className="cloud-images-title">클라우드 이미지</h2>
            <p className="cloud-images-subtitle">{state.providerLabel}로 만들고 편집합니다</p>
          </div>
          <button type="button" className="cloud-images-secondary" onClick={onOpenLocalImages}>
            <Icon name="open" />로컬 이미지 앱 열기
          </button>
        </header>

        {!state.available && (
          <p className="cloud-images-notice" role="status">
            <Icon name="alert" />{state.blockedReason || '지금은 클라우드 이미지 생성을 사용할 수 없습니다.'}
          </p>
        )}

        <div className="cloud-images-field">
          <label htmlFor="cloud-images-prompt" className="cloud-images-label">프롬프트</label>
          <textarea id="cloud-images-prompt" className="cloud-images-textarea" rows={5} maxLength={2000}
            value={state.prompt} disabled={active} onChange={event => onPromptChange(event.target.value)} />
        </div>

        <div className="cloud-images-field">
          <div className="cloud-images-row">
            <span className="cloud-images-label">참조 사진 {references.length}장</span>
            <button type="button" className="cloud-images-secondary" disabled={active} onClick={onPickReference}>
              <Icon name="plus" />참조 사진 추가
            </button>
          </div>
          {references.length > 0 && (
            <ul className="cloud-images-refs">
              {references.map(item => (
                <li key={item.id} className="cloud-images-ref"><img src={item.previewUrl} alt={item.label} /></li>
              ))}
            </ul>
          )}
        </div>

        {job && jobInfo && (
          <div className={`cloud-images-job cloud-images-job--${jobInfo.tone}`} aria-live="polite">
            <span className="cloud-images-job-icon">
              {job.state === 'running' && <span className="cloud-images-spinner" aria-hidden="true" />}
              <Icon name={jobInfo.icon} />
            </span>
            <div className="cloud-images-job-text">
              <strong>{jobInfo.label}</strong>
              {jobInfo.detail && <p>{jobInfo.detail}</p>}
            </div>
            {(job.state === 'queued' || job.state === 'running') && (
              <button type="button" className="cloud-images-secondary" onClick={() => onCancel(job.id)}>취소</button>
            )}
          </div>
        )}

        {job?.state === 'completed' && !result && (
          <p className="cloud-images-notice" role="status">
            <Icon name="alert" />완료 보고를 받았지만 결과 이미지를 확인하지 못했습니다.
          </p>
        )}

        {result && (
          <figure className="cloud-images-result">
            <img src={result.url} alt="생성된 이미지" width={result.width} height={result.height}
              style={{ aspectRatio: `${result.width} / ${result.height}` }} />
            <figcaption>{result.width}×{result.height}</figcaption>
            {result.downloadConfirmed ? (
              <button type="button" className="cloud-images-secondary" disabled><Icon name="check" />저장됨</button>
            ) : (
              <button type="button" className="cloud-images-secondary" onClick={() => onSave(result.id)}>
                <Icon name="down" />저장
              </button>
            )}
          </figure>
        )}
      </div>

      <div className="cloud-images-actions">
        <button type="button" className="cloud-images-primary" disabled={!canGenerate} onClick={generate}>
          <Icon name="arrow" />{references.length > 0 ? '편집해서 생성' : '생성'}
        </button>
      </div>
    </section>
  )
}

export default CloudImages
