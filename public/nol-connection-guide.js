(() => {
  const visible = node => node.getClientRects().length && getComputedStyle(node).visibility !== 'hidden';
  const label = node => [node.getAttribute('aria-label'), node.alt,
    ...Array.from(node.querySelectorAll('img[alt]')).map(img => img.alt), node.innerText].filter(Boolean).join(' ').trim();
  const controls = Array.from(document.querySelectorAll('button,a,[role=button]')).filter(visible).slice(0,300);
  const names = controls.map(label);
  const inputs = Array.from(document.querySelectorAll('input')).filter(visible);
  const text = (document.body?.innerText || '').slice(0,28000);
  const heading = Array.from(document.querySelectorAll('h1,h2,h3,legend,[role=heading],[role=tab][aria-selected=true]'))
    .filter(visible).slice(0,30).map(label).join(' ');
  const inputLabel = input => [input.name,input.id,input.autocomplete,input.placeholder,input.getAttribute('aria-label'),
    ...Array.from(input.labels || [],label)].filter(Boolean).join(' ');
  const sources = [
    ['kakao', /카카오|kakao/i], ['naver', /네이버|naver/i], ['google', /구글|google/i],
    ['apple', /애플|apple/i], ['email', /이메일|e-mail|email/i],
  ];
  const loginLabel = /로그인|계정으로|계속하기|sign in|continue with/i;
  const offered = sources.filter(([id, pattern]) =>
    names.some(name => pattern.test(name) &&
      (loginLabel.test(name) || name.match(pattern)?.[0].toLowerCase() === name.toLowerCase())) ||
    (id === 'email' && inputs.some(input => input.type === 'email'))).map(([id]) => id);
  const alerts = Array.from(document.querySelectorAll('[role=alert],[role=status],[aria-live]')).filter(visible)
    .slice(0,24).map(node => (node.innerText || '').trim()).filter(Boolean);
  const combinedNotices = [...alerts, ...text.split('\n').filter(line => line.length <= 150)].join('\n');
  const additional = [...new Set(alerts.map(value => value.replace(/\s+/g, ' ').trim()))]
    .filter(value => value.length > 3 && value.length <= 180 &&
      !/[@<>]|https?:|(?:\d[ -]?){4}|비밀 ?번호\s*[:=]|인증번호\s*[:=]/i.test(value)).slice(0,12);
  const notices = [];
  if (/인증.{0,15}(?:만료|시간.{0,6}지났)|코드.{0,6}만료/.test(combinedNotices)) notices.push('expired');
  if (/인증번호.{0,16}(?:일치하지|올바르지|틀렸|확인해)|잘못된.{0,7}인증번호/.test(combinedNotices)) notices.push('invalid');
  if (/인증번호.{0,14}(?:전송했|발송했|전송되었|발송되었|보냈)/.test(combinedNotices)) notices.push('sent');
  if (/(?:요청|전송).{0,13}(?:횟수.{0,8}초과|너무 많)|잠시 후.{0,12}재요청/.test(combinedNotices)) notices.push('rate_limit');
  if (/(?:가입|등록|일치하는).{0,12}계정.{0,10}(?:없|찾을 수 없)/.test(combinedNotices)) notices.push('no_account');
  if (/필수.{0,7}입력|필수.{0,7}항목/.test(combinedNotices)) notices.push('required');
  if (/(?:아이디|이메일|비밀번호).{0,18}(?:일치하지|올바르지|틀렸)/.test(combinedNotices)) notices.push('bad_login');
  const onNol = ['nol.yanolja.com','accounts.nol.com'].includes(location.hostname);
  const hasPassword = inputs.some(input => input.type === 'password' || input.autocomplete === 'current-password');
  const otp = inputs.some(input => input.autocomplete === 'one-time-code' ||
    /인증\s*(?:번호|코드)|verification\s*code|one[ -]?time\s*code/i.test(inputLabel(input)));
  const recover = /아이디 찾기|계정 찾기|가입 정보 확인/.test(heading) ||
    inputs.some(input => input.type === 'tel') && /아이디 찾기|계정 찾기/.test(text) && !hasPassword;
  const resultLine = text.split('\n').map(line => line.trim()).find(line =>
    /(?:가입|연동|계정|등록)/.test(line) && /[A-Za-z0-9._%+-]{1,24}[*•]{2,}[^@\s]{0,20}@[A-Za-z0-9.-]+\.[A-Za-z]{2,}/.test(line));
  const resultProvider = resultLine && sources.find(([id,pattern]) => id === "email"
    ? /이메일(?:로)?\s*(?:가입|로그인)/.test(resultLine) : pattern.test(resultLine))?.[0];
  const mask = resultLine?.match(/[A-Za-z0-9._%+-]{1,24}[*•]{2,}[^@\s]{0,20}@[A-Za-z0-9.-]+\.[A-Za-z]{2,}/)?.[0];
  const onNaver = location.hostname === 'nid.naver.com';
  const naverOtp = onNaver && !hasPassword && (otp || inputs.some(input => /(^|\s)otp|otpnumber/i.test(inputLabel(input))));
  const naverQr = onNaver && !hasPassword && /QR\s*코드|QR\s*로그인/i.test(heading);
  const naverApproval = onNaver && !hasPassword &&
    (/2\s*단계\s*인증|인증\s*알림|two[ -]step verification/i.test(heading) ||
      names.some(name => /(?:알림|요청).{0,7}(?:다시 보내|재전송)|인증 알림이 오지 않나요|resend notification/i.test(name)));
  const naverUnknownChallenge = onNaver && !hasPassword &&
    /본인\s*확인|기기에서\s*확인|추가\s*인증|보안\s*확인|verify your identity|security check/i.test(heading);
  let stage='entry';
  if (/일시적으로 서비스를 이용하실 수 없습니다|access denied/i.test(text)) stage='error';
  else if (naverOtp) stage='naver_otp';
  else if (naverQr) stage='naver_qr';
  else if (naverApproval) stage='naver_approve';
  else if (naverUnknownChallenge) stage='naver_challenge';
  else if (otp) stage='code';
  else if (/카카오톡.{0,35}(?:확인 버튼|인증 요청|인증 메시지)|앱에서.{0,18}로그인 확인/.test(text)) stage='app_approve';
  else if (/QR\s*코드.{0,12}로그인|QR\s*로그인/i.test(heading)) stage='qr';
  else if (/동의하기|권한 요청|개인정보 제공 동의/.test(heading)) stage='consent';
  else if (/비밀번호 (?:찾기|재설정|변경)/.test(heading)) stage='reset';
  else if (!onNol) stage='provider';
  else if (location.hostname === 'nol.yanolja.com' && /^\/(?:member\/)?mypage\/?$/.test(location.pathname) &&
      !hasPassword && names.some(name => /^로그아웃$/.test(name)) &&
      names.some(name => /예약 ?내역|회원 ?정보/.test(name))) stage='connected';
  else if (resultLine && !hasPassword) stage='found';
  else if (recover && !hasPassword) stage='recover';
  else if (hasPassword || offered.length) stage='login';
  return {stage, providers:offered, notices, additional, foundProvider:resultProvider || null, maskedAccount:mask || null};
})()
