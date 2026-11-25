const assert=require('node:assert/strict')
const {test}=require('node:test')
const {readFileSync}=require('node:fs')
const {chromium}=require('playwright')
const script=readFileSync(require('node:path').join(__dirname,'../../public/nol-connection-guide.js'),'utf8')
const loginScript=readFileSync(require('node:path').join(__dirname,'../../public/app-web-login.js'),'utf8')

test('NOL guidance follows the actual pages without exporting form values', async()=>{
  const browser=await chromium.launch({headless:true})
  try {
    const page=await browser.newPage()
    let fixture=''
    for (const origin of ['https://nol.yanolja.com','https://nid.naver.com']) {
      await page.route(origin+'/**',route=>route.fulfill({contentType:'text/html; charset=utf-8',body:fixture}))
    }
    async function check(html,url='https://nol.yanolja.com/member/mypage'){
      fixture=html
      await page.goto(url,{waitUntil:'domcontentloaded'})
      return page.evaluate(script)
    }
    assert.equal((await check('<button aria-label="카카오 로그인">카카오</button><input type="password" value="fixture-password">')).stage,'login')
    assert.equal((await check('<h1>아이디 찾기</h1><input type="tel">')).stage,'recover')
    assert.equal((await check('<h1>비밀번호 재설정</h1><input type="password">')).stage,'reset')
    const code=await check('<p role="alert">인증번호를 보냈어요.</p><input autocomplete="one-time-code" value="491875">')
    assert.equal(code.stage,'code')
    assert.deepEqual(code.notices,['sent'])
    assert.equal(JSON.stringify(code).includes('491875'),false)
    const help=await check('<p role="alert">선택한 필수 항목을 확인해 주세요.</p><p role="alert">인증번호: 491875</p>')
    assert.deepEqual(help.additional,['선택한 필수 항목을 확인해 주세요.'])
    assert.equal(JSON.stringify(help).includes('491875'),false)
    const result=await check('<p>카카오 연동 계정 demo****@example.com</p>')
    assert.equal(result.stage,'found')
    assert.equal(result.foundProvider,'kakao')
    assert.equal((await check('<p>일시적으로 서비스를 이용하실 수 없습니다</p>')).stage,'error')
    const account='<button>로그아웃</button><a>예약 내역</a><footer><a>Kakao support</a></footer>'
    assert.equal((await check(account)).stage,'connected')
    assert.notEqual((await check(account,'https://nol.yanolja.com/member/login')).stage,'connected')
    const naver='https://nid.naver.com/nidlogin.login'
    const approval='<h1>2단계 인증</h1><button>알림 다시 보내기</button><button>OTP 인증번호를 입력하여 로그인하기</button>'
    assert.equal((await check(approval,naver)).stage,'naver_approve')
    assert.notEqual((await check(approval)).stage,'naver_approve')
    assert.equal((await check('<h1>QR코드</h1><img alt="QR 코드">',naver)).stage,'naver_qr')
    const otp=await check(approval+'<input name="otpNumber" value="567890">',naver)
    assert.equal(otp.stage,'naver_otp')
    assert.equal(JSON.stringify(otp).includes('567890'),false)
    assert.equal((await check(approval+'<input type="password">',naver)).stage,'provider')
    assert.equal((await check('<legend>기기에서 확인</legend><button>다른 방법</button>',naver)).stage,'naver_challenge')
    assert.equal((await check('<div role="heading">Two-step verification</div><div role="button">Resend notification</div>',naver)).stage,'naver_approve')
    assert.equal((await check('<label for="field">Verification code</label><input id="field">',naver)).stage,'naver_otp')
    assert.equal((await check('<h1>Security check</h1><input autocomplete="current-password">',naver)).stage,'provider')
  } finally {await browser.close()}
})

test('authentication requires a visible sign-out action on the bound origin', async()=>{
  const browser=await chromium.launch({headless:true})
  try {
    const page=await browser.newPage()
    let fixture=''
    await page.route('https://nid.naver.com/**',route=>route.fulfill({contentType:'text/html; charset=utf-8',body:fixture}))
    async function check(html,origin='https://nid.naver.com'){
      fixture=html
      await page.goto('https://nid.naver.com/nidlogin.login',{waitUntil:'load'})
      return page.evaluate('('+loginScript+')('+JSON.stringify({action:'authenticated',origin})+')')
    }
    assert.equal(await check('<h1>2단계 인증</h1><button>알림 다시 보내기</button>'),false)
    assert.equal(await check('<p>로그인을 완료하지 않았습니다.</p>'),false)
    assert.equal(await check('<button hidden>로그아웃</button>'),false)
    assert.equal(await check('<button>로그아웃</button><label for="mfa">인증번호</label><input id="mfa">'),false)
    assert.equal(await check('<button>로그아웃</button>','https://www.naver.com'),false)
    assert.equal(await check('<button>로그아웃</button>'),true)
  } finally {await browser.close()}
})

test('printing refuses an opaque frame in Chromium', async()=>{
  const browser=await chromium.launch({headless:true})
  try {
    const page=await browser.newPage()
    let fixture=''
    await page.route('https://nol.yanolja.com/**',route=>route.fulfill({contentType:'text/html; charset=utf-8',body:fixture}))
    await page.route('https://payment-frame.example/**',route=>route.fulfill({contentType:'text/html',body:'<p>Owner confirmation</p>'}))
    const exportScript=readFileSync(require('node:path').join(__dirname,'../../public/app-web-agent.js'),'utf8')
    async function check(html){
      fixture=html
      await page.goto('https://nol.yanolja.com/member/mypage',{waitUntil:'load'})
      return page.evaluate('('+exportScript+')({action:"securityCheck"})')
    }
    assert.equal((await check('<iframe srcdoc="<p>Ordinary document</p>"></iframe>')).safe,true)
    assert.equal((await check('<iframe srcdoc="<input autocomplete=cc-number>"></iframe>')).safe,false)
    assert.equal((await check('<iframe src="https://payment-frame.example/"></iframe>')).safe,false)
  } finally {await browser.close()}
})
