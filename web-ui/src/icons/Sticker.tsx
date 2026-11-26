import type { ReactNode } from 'react'
import { STICKERS, type IllustrationShape } from './generated'
import './sticker.css'

// 모델 답변 속 이모티콘을 집순이 스티커로 바꿔 그린다. 원본은 design/stickers.json.
// 바뀌는 것은 화면뿐이다: 메시지 문구와 복사 내용은 그대로이고, 원래 글자는 투명한 겹친 글자로 남아
// 선택·복사하면 원문이 나온다. 화면 읽기 프로그램에는 한국어 이름(예: "졸업")을 알려준다.
export type StickerName = keyof typeof STICKERS

// 코드 포인트는 \u{...} 이스케이프로만 적는다(소스에 이모티콘 글자를 두지 않는다).
const groups: [StickerName, string[]][] = [
  ['gradCap', ['\u{1F393}', '\u{1F3EB}', '\u{1F9D1}\u{200D}\u{1F393}']],
  ['calendar', ['\u{1F4C5}', '\u{1F4C6}', '\u{1F5D3}', '\u{1F4CB}']],
  ['check', ['\u{2705}', '\u{2714}', '\u{2611}', '\u{2713}', '\u{1F197}', '\u{1F44D}', '\u{1F44C}']],
  ['sparkle', ['\u{2728}', '\u{2B50}', '\u{1F31F}', '\u{1F4AB}', '\u{1F4A1}']],
  ['heart', ['\u{2764}', '\u{2665}', '\u{1F495}', '\u{1F496}', '\u{1F497}', '\u{1F493}', '\u{1F49B}', '\u{1F49A}', '\u{1F499}',
    '\u{1F49C}', '\u{1F9E1}', '\u{1F90D}', '\u{1F90E}', '\u{1F5A4}', '\u{1F970}', '\u{1F60D}']],
  ['house', ['\u{1F3E0}', '\u{1F3E1}', '\u{1F3D8}', '\u{1F3E2}']],
  ['bed', ['\u{1F6CF}', '\u{1F3E8}', '\u{1F6CC}', '\u{1F3D5}', '\u{26FA}']],
  ['mapPin', ['\u{1F4CD}', '\u{1F4CC}', '\u{1F5FA}', '\u{1F9ED}']],
  ['money', ['\u{1F4B0}', '\u{1F4B5}', '\u{1F4B8}', '\u{1F4B3}', '\u{1FA99}', '\u{1F4B4}', '\u{1F4B6}', '\u{1F4B7}']],
  ['warning', ['\u{26A0}', '\u{2757}', '\u{203C}', '\u{1F6A8}', '\u{26D4}', '\u{1F6AB}', '\u{2753}', '\u{2049}']],
  ['clock', ['\u{23F0}', '\u{23F1}', '\u{23F2}', '\u{231A}', '\u{231B}', '\u{23F3}', '\u{1F570}',
    ...Array.from({ length: 24 }, (_, i) => String.fromCodePoint(0x1F550 + i))]],
  ['phone', ['\u{1F4DE}', '\u{260E}', '\u{1F4F1}', '\u{1F4F2}', '\u{1F4F3}']],
  ['mail', ['\u{2709}', '\u{1F4E7}', '\u{1F4E9}', '\u{1F4E8}', '\u{1F48C}', '\u{1F4EC}', '\u{1F4EE}']],
  ['gift', ['\u{1F381}', '\u{1F380}']],
  ['food', ['\u{1F37D}', '\u{1F374}', '\u{1F35A}', '\u{1F35C}', '\u{1F371}', '\u{1F355}', '\u{1F354}', '\u{2615}', '\u{1F370}',
    '\u{1F373}', '\u{1F958}', '\u{1F372}', '\u{1F359}', '\u{1F35D}', '\u{1F366}', '\u{1F369}', '\u{1F36A}', '\u{1F964}']],
  ['car', ['\u{1F697}', '\u{1F695}', '\u{1F699}', '\u{1F68C}', '\u{1F690}', '\u{1F6FB}', '\u{1F68D}', '\u{1F696}']],
  ['train', ['\u{1F686}', '\u{1F684}', '\u{1F685}', '\u{1F687}', '\u{1F683}', '\u{1F689}', '\u{1F688}', '\u{1F682}', '\u{2708}',
    '\u{1F6EB}', '\u{1F6EC}']],
  ['book', ['\u{1F4DA}', '\u{1F4D6}', '\u{1F4D8}', '\u{1F4D7}', '\u{1F4D5}', '\u{1F4D9}', '\u{1F4D2}', '\u{1F4D3}', '\u{1F4D4}',
    '\u{1F4DD}', '\u{270F}', '\u{1F4C4}', '\u{1F4D1}']],
  ['party', ['\u{1F389}', '\u{1F38A}', '\u{1F973}', '\u{1F388}', '\u{1F38F}']],
  ['smile', ['\u{1F600}', '\u{1F603}', '\u{1F604}', '\u{1F601}', '\u{1F60A}', '\u{1F642}', '\u{263A}', '\u{1F606}', '\u{1F60C}',
    '\u{1F917}', '\u{1F60E}', '\u{1F609}', '\u{1F643}', '\u{1F92D}', '\u{1F60B}']],
  ['sun', ['\u{2600}', '\u{1F324}', '\u{26C5}', '\u{1F305}', '\u{1F304}', '\u{1F31E}']],
]

const base = (value: string) => value.replace(/[\u{FE0E}\u{FE0F}\u{1F3FB}-\u{1F3FF}]/gu, '')
const byEmoji = new Map<string, StickerName>()
for (const [name, list] of groups) for (const emoji of list) byEmoji.set(base(emoji), name)

/** Which sticker draws this emoji, or `undefined` when it has none (it is then drawn as a neutral sparkle). */
export function stickerFor(emoji: string): StickerName | undefined {
  const key = base(emoji)
  return byEmoji.get(key) ?? byEmoji.get(key.split('\u200D')[0])
}

// 이모티콘 표시가 기본인 글자, 또는 FE0F가 붙은 그림 글자. 이어 붙인 ZWJ 묶음, 피부색, 국기, 키캡을 한 덩어리로 잡는다.
// 저작권·등록상표·상표·별(U+00A9, U+00AE, U+2122, U+2605) 같은 글자 기호는 FE0F가 없으면 그대로 둔다.
// 체크(U+2713, U+2714), 하트(U+2764), 경고(U+26A0) 등 자주 쓰는 글자형은 따로 잡는다.
const emojiPattern = new RegExp(
  '(?:\\p{Regional_Indicator}{2})' +
  '|(?:[#*0-9]\\u{FE0F}?\\u{20E3})' +
  '|(?:(?:\\p{Emoji_Presentation}|\\p{Extended_Pictographic}\\u{FE0F}|[\\u{2713}\\u{2714}\\u{2764}\\u{2665}\\u{26A0}\\u{2709}\\u{260E}\\u{2600}\\u{263A}\\u{2708}\\u{270F}\\u{203C}\\u{2049}])' +
  '\\u{FE0F}?\\p{Emoji_Modifier}?' +
  '(?:\\u{200D}\\p{Extended_Pictographic}\\u{FE0F}?\\p{Emoji_Modifier}?)*)',
  'gu')

function StickerArt({ shapes }: { shapes: readonly IllustrationShape[] }) {
  return <svg className="sticker-art" viewBox="0 0 24 24" aria-hidden="true" focusable="false">
    {shapes.map((shape, i) => <path key={i} d={shape.d} fill={shape.fill ?? 'none'} stroke={shape.stroke} strokeWidth={shape.sw}
      strokeLinecap={shape.stroke ? 'round' : undefined} strokeLinejoin={shape.stroke ? 'round' : undefined} opacity={shape.opacity} />)}
  </svg>
}

export function Sticker({ name, source }: { name: StickerName; source?: string }) {
  const art = STICKERS[name]
  return <span className={`sticker sticker--${name}`} role="img" aria-label={art.label} data-sticker={name}>
    <StickerArt shapes={art.shapes} />
    {source && <span className="sticker-source" aria-hidden="true">{source}</span>}
  </span>
}

/** One emoji drawn as a sticker. An emoji without a sticker keeps its own name for screen readers. */
function EmojiSticker({ emoji }: { emoji: string }) {
  const name = stickerFor(emoji)
  if (name) return <Sticker name={name} source={emoji} />
  return <span className="sticker sticker--neutral" role="img" aria-label={emoji} data-sticker="neutral">
    <StickerArt shapes={STICKERS.sparkle.shapes} />
    <span className="sticker-source" aria-hidden="true">{emoji}</span>
  </span>
}

/** Splits text into plain strings and sticker elements. Text without emoji comes back as the same single string. */
export function withStickers(text: string, keyPrefix: string | number = 's'): ReactNode[] {
  const nodes: ReactNode[] = []
  let end = 0
  for (const match of text.matchAll(emojiPattern)) {
    const index = match.index
    if (index > end) nodes.push(text.slice(end, index))
    nodes.push(<EmojiSticker key={`${keyPrefix}-${index}`} emoji={match[0]} />)
    end = index + match[0].length
  }
  if (end === 0) return [text]
  if (end < text.length) nodes.push(text.slice(end))
  return nodes
}

/** Model, owner or host text shown on screen: every emoji becomes a sticker, the text itself is unchanged. */
export function StickerText({ text }: { text: string }) {
  return <>{withStickers(text)}</>
}

/** Text for places that cannot hold a sticker (tooltips): emoji are removed so no system emoji is drawn. */
export function withoutEmoji(text: string): string {
  return text.replace(emojiPattern, ' ').replace(/[ 	]{2,}/g, ' ').trim()
}
