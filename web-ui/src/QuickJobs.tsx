import { Icon, type IconName } from './Icon'

const jobs: { label: string; request: string; icon: IconName }[] = [
  {
    label: '안 쓴 구매후기 챙기기',
    request: '아직 후기를 쓰지 않은 구매 내역을 찾아 줘. 내가 알려 주는 사용 경험으로 후기 초안을 만들고, 올리기 전에 보여 줘.',
    icon: 'edit',
  },
  {
    label: '오늘 출석 체크 챙기기',
    request: '오늘 확인할 출석 체크가 있는지 봐 줘. 어느 서비스인지 모르면 먼저 물어봐 줘. 서비스에서 허용하는 간단한 체크인만 처리하고, 직접 참여하거나 인증해야 하는 출석은 따로 알려 줘.',
    icon: 'check',
  },
  {
    label: '안 쓰는 사이트 탈퇴 정리',
    request: '안 쓰는 사이트를 정리하고 싶어. 탈퇴할 사이트를 고를 수 있게 도와줘. 탈퇴 전에 계정과 사라질 자료를 알려 주고, 내가 확인한 뒤에 탈퇴해 줘.',
    icon: 'archive',
  },
]

export function QuickJobs({ disabled, onChoose }: {
  disabled: boolean
  onChoose(text: string): void
}) {
  return <section className="quick-jobs" aria-label="집순이가 자주 맡는 알바">
    <h2>집순이가 자주 맡는 알바</h2>
    {jobs.map(({ label, request, icon }) =>
      <button className="suggestion-chip" type="button" key={label} disabled={disabled} onClick={() => onChoose(request)}>
        <Icon name={icon} />{label}
      </button>)}
  </section>
}
