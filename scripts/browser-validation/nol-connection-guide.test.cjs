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

test('account menu guides the owner without confirming login until sign-out is visible', async()=>{
  const browser=await chromium.launch({headless:true})
  try {
    const page=await browser.newPage()
    let fixture=''
    await page.route('https://nid.naver.com/**',route=>route.fulfill({contentType:'text/html; charset=utf-8',body:fixture}))
    const state=(origin='https://nid.naver.com')=>
      page.evaluate('('+loginScript+')('+JSON.stringify({action:'authenticationState',origin})+')')
    async function check(html,origin){
      fixture=html
      await page.goto('https://nid.naver.com/nidlogin.login',{waitUntil:'load'})
      return state(origin)
    }
    assert.equal(await check('<h1>2단계 인증</h1><button>알림 다시 보내기</button>'),'unverified')
    assert.equal(await check('<p>로그인을 완료하지 않았습니다.</p>'),'unverified')
    assert.equal(await check('<button hidden>로그아웃</button>'),'unverified')
    assert.equal(await check('<button>로그아웃</button><label for="mfa">인증번호</label><input id="mfa">'),'unverified')
    assert.equal(await check('<button>로그아웃</button><span id="code">Security code</span><input aria-labelledby="code">'),'unverified')
    assert.equal(await check('<button>로그아웃</button>','https://www.naver.com'),'unverified')
    assert.equal(await check('<button>로그아웃</button>'),'signed_in')
    assert.equal(await check('<button>계정 메뉴</button>'),'account_menu')
    assert.equal(await check('<button aria-controls="account" aria-expanded="false">메뉴</button><nav id="account" hidden><button role="menuitem">로그아웃</button></nav>'),'account_menu')
    await page.evaluate(()=>{
      document.getElementById('account').hidden=false
      document.querySelector('[aria-controls]').setAttribute('aria-expanded','true')
    })
    assert.equal(await state(),'signed_in')
  } finally {await browser.close()}
})

test('bound post-login lookup can be read without a repeated logout control', async()=>{
  const browser=await chromium.launch({headless:true})
  try {
    const page=await browser.newPage()
    let fixture=''
    await page.route('https://booking.naver.com/**',route=>route.fulfill({contentType:'text/html; charset=utf-8',body:fixture}))
    async function check(html,origin='https://booking.naver.com'){
      fixture=html
      await page.goto('https://booking.naver.com/my/reservations?id=fixture',{waitUntil:'load'})
      return page.evaluate('('+loginScript+')('+JSON.stringify({action:'authenticatedTaskReady',origin})+')')
    }
    assert.equal(await check('<main><h1>내 예약</h1><p>예약이 확인됐어요.</p></main>'),true)
    assert.equal(await check('<main><h1>내 예약</h1></main>','https://mail.naver.com'),false)
    assert.equal(await check('<main><h1>로그인해 주세요</h1></main>'),false)
    assert.equal(await check('<main><h1>내 예약</h1><label for="code">인증번호</label><input id="code"></main>'),false)
    assert.equal(await check('<main aria-busy="true"><h1>내 예약</h1></main>'),false)
    assert.equal(await check('<main><p>예약을 찾을 수 없습니다. 다시 로그인해 주세요.</p></main>'),false)
    assert.equal(await check('<main><p>예약이 확인됐어요.</p></main><p role="alert">조회 오류. 다시 시도해 주세요.</p>'),false)
    assert.equal(await check('<main><h1>내 예약</h1><p>예약 내역이 없습니다.</p></main>'),true)
    assert.equal(await check('<main><h1>내 예약</h1><p>예약번호</p></main>'),false)
    assert.equal(await check('<main><h1>내 주문</h1><ul><li>고객센터 도움말</li></ul></main>'),false)
    assert.equal(await check('<main><h1>내 주문</h1><table><tbody><tr><td>목록을 불러오는 중입니다.</td></tr></tbody></table></main>'),false)
    assert.equal(await check('<main><aside><p>예약 내역이 없습니다.</p></aside><ul><li>고객센터 도움말</li></ul></main>'),false)
    assert.equal(await check('<main><p role="status">내역을 불러오는 중입니다.</p><p>예약 내역이 없습니다.</p></main>'),false)
    assert.equal(await check('<main><h1>내 주문</h1><ul><li>주문번호: ORDER-42 <time datetime="2026-11-26">2026-11-26</time> 배송 완료</li></ul></main>'),true)
    assert.equal(await check('<main><table><tbody><tr><td>2026.11.26</td><td>예약 확정</td></tr></tbody></table></main>'),true)
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
    assert.equal((await check('<label for="code">인증 번호</label><input id="code" inputmode="numeric">')).safe,false)
    assert.equal((await check('<span id="auth-name">인증코드</span><input aria-labelledby="auth-name">')).safe,false)
    assert.equal((await check('<iframe srcdoc="<input name=otpNumber>"></iframe>')).safe,false)
    assert.equal((await check('<label for="zip">우편번호</label><input id="zip" type="number">')).safe,true)
    assert.equal((await check('<iframe src="https://payment-frame.example/"></iframe>')).safe,false)
    fixture='<verification-widget></verification-widget>'
    await page.goto('https://nol.yanolja.com/member/mypage',{waitUntil:'load'})
    await page.evaluate(()=>{
      document.querySelector('verification-widget').attachShadow({mode:'open'}).innerHTML='<p>Ordinary component</p>'
    })
    const protectedExport=()=>page.evaluate('('+exportScript+')({action:"securityCheck"})')
    assert.equal((await protectedExport()).safe,true)
    await page.evaluate(()=>{
      const root=document.querySelector('verification-widget').shadowRoot
      const nested=root.appendChild(document.createElement('nested-widget')).attachShadow({mode:'open'})
      nested.innerHTML='<span id="code">인증번호</span><input aria-labelledby="code">'
    })
    assert.equal((await protectedExport()).safe,false)
    await page.reload({waitUntil:'load'})
    await page.evaluate(()=>{
      document.querySelector('verification-widget').attachShadow({mode:'closed'}).innerHTML='<p>Private component</p>'
    })
    assert.equal((await protectedExport()).safe,true)
  } finally {await browser.close()}
})

test('screen PDF uses inert readable text independent of live components', async()=>{
  const browser=await chromium.launch({headless:true})
  try {
    const page=await browser.newPage()
    const source=readFileSync(require('node:path').join(__dirname,'../../public/app-web-agent.js'),'utf8')
    await page.setContent('<main><h1>Receipt</h1><p>Booking confirmed</p><div id="widget"></div><toast-announcer>Notification</toast-announcer><iframe srcdoc="<p>Embedded page</p>"></iframe></main>')
    await page.evaluate(()=>{
      const root=document.getElementById('widget').attachShadow({mode:'closed'})
      root.innerHTML='<p>Personal verification</p><input autocomplete="one-time-code" value="123456">'
    })
    await page.evaluate(()=>{
      const invisible=document.createElement('div')
      invisible.style.opacity='0'
      invisible.innerHTML='<span>NONPUBLIC_FIXTURE</span>'
      const filtered=document.createElement('p')
      filtered.style.filter='opacity(0)'
      filtered.textContent='FILTERED_FIXTURE'
      const clipped=document.createElement('p')
      clipped.style.clipPath='inset(100%)'
      clipped.textContent='CLIPPED_FIXTURE'
      document.querySelector('main').append(invisible,filtered,clipped)
    })
    const snapshot=await page.evaluate('('+source+')({action:"printSnapshot"})')
    assert.match(snapshot.text,/Receipt/)
    assert.match(snapshot.text,/Booking confirmed/)
    assert.doesNotMatch(snapshot.text,/verification|123456|Notification|Embedded page|NONPUBLIC_FIXTURE|FILTERED_FIXTURE|CLIPPED_FIXTURE/)
    await page.setContent('<main><h1>Receipt</h1><div style="height:0;overflow:hidden"><p>HIDDEN_BY_PARENT</p></div><div style="height:10px;overflow:hidden"><span>PARTIALLY_CLIPPED</span></div><div style="height:40px;overflow:auto"><span>VISIBLE_IN_CONTAINER</span></div></main>')
    const clipped=await page.evaluate('('+source+')({action:"printSnapshot"})')
    assert.match(clipped.text,/VISIBLE_IN_CONTAINER/)
    assert.doesNotMatch(clipped.text,/HIDDEN_BY_PARENT|PARTIALLY_CLIPPED/)
    await page.setContent('<main><h1>Receipt</h1><div style="contain:paint;height:0"><p>HIDDEN_PAINT</p></div><div style="contain:content;height:0"><p>HIDDEN_CONTENT</p></div><div style="contain:strict;height:0"><p>HIDDEN_STRICT</p></div><div style="contain:paint;height:80px"><span>VISIBLE_PAINT</span></div></main>')
    const paint=await page.evaluate('('+source+')({action:"printSnapshot"})')
    assert.match(paint.text,/VISIBLE_PAINT/)
    assert.doesNotMatch(paint.text,/HIDDEN_PAINT|HIDDEN_CONTENT|HIDDEN_STRICT/)
    await page.setContent('<label for="otp">인증번호</label><input id="otp">')
    const protectedSnapshot=await page.evaluate('('+source+')({action:"printSnapshot"})')
    assert.equal(protectedSnapshot.error,'PAGE_SECURITY_CHECK_INCOMPLETE')
  } finally {await browser.close()}
})
