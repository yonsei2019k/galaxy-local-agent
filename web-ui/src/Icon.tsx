const paths = {
  menu: 'M4 6h16M4 12h16M4 18h12',
  plus: 'M12 5v14M5 12h14',
  close: 'm6 6 12 12M18 6 6 18',
  chat: 'M20 11.5a7.5 7.5 0 0 1-7.5 7.5H5l1.5-3A7.5 7.5 0 1 1 20 11.5Z',
  settings: 'M12 8v0m0 8v0M4 8h16M4 16h16M8 5v6m8 2v6',
  image: 'M5 4h14a1 1 0 0 1 1 1v14a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V5a1 1 0 0 1 1-1Zm-1 12 5-5 4 4 2-2 5 5M15 8v.01',
  tools: 'M12 3v4m-2-2h4M19 10v4m-2-2h4M5 15v4m-2-2h4m1-4 6 6m-1-8-6 6',
  folder: 'M4 7a1 1 0 0 1 1-1h5l2 2h7a1 1 0 0 1 1 1v9a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1Z',
  history: 'M4 11a8 8 0 1 1 2.1 6M4 6v5h5m3-3v5l3 2',
  native: 'M7 3h10a1 1 0 0 1 1 1v16a1 1 0 0 1-1 1H7a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1Zm4 15h2',
  arrow: 'M12 19V5m-6 6 6-6 6 6',
  arrowDown: 'M12 5v14m-6-6 6 6 6-6',
  back: 'M19 12H5m6-6-6 6 6 6',
  group: 'M12 11a3 3 0 1 0 0-6 3 3 0 0 0 0 6ZM5 19v-1a4 4 0 0 1 4-4h6a4 4 0 0 1 4 4v1M5 6a2.5 2.5 0 0 0 0 5m14-5a2.5 2.5 0 0 1 0 5',
  refresh: 'M19 9a7.5 7.5 0 1 0 .1 6M19 4v5h-5',
  stop: 'M6 6h12v12H6z',
  steer: 'M7 19v-3a4 4 0 0 1 4-4h8m-5-5 5 5-5 5',
  clip: 'm9 13 5-5a2 2 0 0 1 3 3l-7 7a4 4 0 0 1-6-6l8-8',
  file: 'M7 3h6l4 4v14H7zm6 0v5h4m-7 4h4m-4 4h4',
  down: 'm6 9 6 6 6-6',
  check: 'm5 12 4 4L19 6',
  clock: 'M12 4a8 8 0 1 0 0 16 8 8 0 0 0 0-16Zm0 4v4l3 2',
  activity: 'M3 12h4l3-6 4 12 3-6h4',
  pause: 'M9 6v12m6-12v12',
  alert: 'M12 4 3 19h18L12 4Zm0 6v3m0 3v.01',
  shield: 'M12 3c3 2 5 2.5 8 3v5c0 5-3.5 8-8 10-4.5-2-8-5-8-10V6c3-.5 5-1 8-3Z',
  copy: 'M8 8h11v12H8zm-3 8H4V4h11v1',
  open: 'M13 5h6v6m0-6-9 9m7 0v5H5V7h5',
  robot: 'M7 8h10a3 3 0 0 1 3 3v4a3 3 0 0 1-3 3H7a3 3 0 0 1-3-3v-4a3 3 0 0 1 3-3Zm5 0V4m-3 9v.01m6-.01v.01m-5 2.5h4',
  chip: 'M7 7h10v10H7zm4 4h2v2h-2zM9 3v4m6-4v4m-6 10v4m6-4v4M3 9h4m-4 6h4m10-6h4m-4 6h4',
}

export type IconName = keyof typeof paths

export function Icon({ name, className }: { name: IconName; className?: string }) {
  return (
    <svg className={className} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7"
      strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d={paths[name]} />
    </svg>
  )
}
