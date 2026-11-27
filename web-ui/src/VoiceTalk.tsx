import { Character } from './character/Character'
import { useCharacter } from './character/useCharacter'
import type { Snapshot } from './bridge'
import { Icon } from './Icon'
import { StickerText } from './icons/Sticker'

type Talk = NonNullable<Snapshot['voiceTalk']>
const stages: Record<Talk['stage'], string> = {
  idle: '마이크를 누르고 말해 주세요.',
  permission: '마이크 권한을 확인하고 있어요.',
  listening: '듣고 있어요.',
  finishing: '음성을 글로 바꾸고 있어요.',
  review: '인식된 말을 확인해 주세요.',
  thinking: '집순이가 답변을 준비하고 있어요.',
  preparing: '읽어주기를 준비하고 있어요.',
  speaking: '답변을 읽고 있어요.',
}

export function VoiceTalk({ talk, onListen, onSend, onClose }: {
  talk: Talk; onListen(): void; onSend(): void; onClose(): void
}) {
  const [character] = useCharacter()
  const canListen = !talk.blocked && ['idle', 'review', 'listening', 'preparing', 'speaking'].includes(talk.stage)
  const listenLabel = (talk.stage === 'speaking' || talk.stage === 'preparing') ? '읽기 멈추고 말하기'
    : talk.stage === 'listening' ? '말하기 완료' : talk.stage === 'review' ? '다시 말하기' : '말하기'
  return <section className="voice-talk" aria-label="집순이 음성 대화">
    <div className="voice-talk-heading">
      <span className="voice-avatar" data-stage={talk.stage} aria-hidden="true">
        <Character choice={character} size={40} state={talk.stage === 'thinking' ? 'work' : 'none'} decorative />
      </span>
      <div><strong>음성 대화</strong><div role="status">{talk.blocked && talk.stage === 'idle' ? '채팅의 작업이나 확인을 먼저 마쳐 주세요.' : stages[talk.stage]}</div></div>
      <button type="button" className="text-button" onClick={onClose}>끝내기</button>
    </div>
    {(talk.transcript || talk.reply) && <div className="voice-caption" dir="auto"><StickerText text={talk.transcript || talk.reply} /></div>}
    <div className="voice-talk-actions">
      <button type="button" className="text-button" onClick={onListen} disabled={!canListen}>
        <Icon name="microphone" />{listenLabel}
      </button>
      {talk.stage === 'review' && <button type="button" className="text-button" disabled={talk.blocked || !talk.transcript.trim()}
        onClick={onSend}>이대로 보내기</button>}
    </div>
  </section>
}
