package com.user.localagent.connections

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.*
import com.user.localagent.core.ToolCall
import com.user.localagent.core.connections.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.time.ZoneId
import java.time.ZonedDateTime

/** Owner-operated setup. Credentials never enter the assistant conversation. */
class ConnectionsActivity:Activity() {
    private val tasks=CoroutineScope(SupervisorJob()+Dispatchers.Main)
    private val hub by lazy {ConnectionHub.get(this)}
    private val googleBatch by lazy {GoogleBatchAuthorization(this,hub)}
    private lateinit var servicePicker:Spinner
    private lateinit var input:EditText
    private lateinit var token:EditText
    private lateinit var chat:EditText
    private lateinit var status:TextView
    private lateinit var output:TextView
    private lateinit var connectRead:Button
    private lateinit var connectWrite:Button
    private var pendingConsentToken:String?=null
    private var busy=false
    private val selected get()=ConnectedService.entries[servicePicker.selectedItemPosition]
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        // A token and public service/scope binding are safe to restore; no credentials are saved.
        pendingConsentToken=savedInstanceState?.getString("google.consent.token")
        val restoredConsent=GoogleConsentRequests.store.peek(pendingConsentToken)
        val consentLost=pendingConsentToken!=null&&(restoredConsent==null||
            restoredConsent.serviceId!=savedInstanceState?.getString("google.consent.service")||
            restoredConsent.write!=savedInstanceState?.getBoolean("google.consent.write"))
        if(consentLost)pendingConsentToken=null
        val root=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;setPadding(28,32,28,28)}
        val scroll=ScrollView(this).apply {addView(root)};setContentView(scroll)
        fun label(text:String,size:Float=15f)=TextView(this).apply {this.text=text;textSize=size;setPadding(0,10,0,10)}.also(root::addView)
        fun button(text:String,action:()->Unit)=Button(this).apply {this.text=text;setOnClickListener {if(!busy)action()}}.also(root::addView)
        label("앱과 자료 연결",24f)
        label("계정 연결과 실제 자료 조회 성공을 따로 표시합니다. 구글 Chat·Keep·AI Studio·Home·Blogger·Books·Classroom·Meet는 포함하지 않습니다.")
        servicePicker=Spinner(this).apply {adapter=ArrayAdapter(this@ConnectionsActivity,android.R.layout.simple_spinner_dropdown_item,ConnectedService.entries.map {it.label})};root.addView(servicePicker)
        status=label("")
        connectRead=button("계정 연결 · 읽기") {authorize(false)}
        button("구글 전체 연결 · 서비스별 권한 확인") {run {check(pendingConsentToken==null){"이전 Google 권한 확인을 먼저 마쳐주세요."};googleBatch.begin();advanceGoogleBatch()}}
        connectWrite=button("계정 연결 · 읽기와 새 자료 작성") {authorize(true)}
        token=EditText(this).apply {hint="서비스 연결 토큰 · 채팅으로 보내지 마세요";inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD;isSaveEnabled=false;importantForAutofill=View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS};root.addView(token)
        chat=EditText(this).apply {hint="Telegram에서 허용할 대화 ID";inputType=InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED;isSaveEnabled=false};root.addView(chat)
        button("토큰 연결 확인·저장") {val service=selected;val secret=token.text.toString();val allowed=chat.text.toString().takeIf {it.isNotBlank()};token.text.clear();run {require(!service.google);hub.configureToken(service,secret,allowed);output.text="연결 정보를 서비스에서 확인하고 암호화 저장했습니다."}}
        button("이 앱의 연결 해제") {val service=selected;run {withContext(Dispatchers.IO){hub.disconnect(service)};output.text="이 앱의 접근과 캐시를 해제했습니다. 구글 계정에서의 권한 철회는 별도로 할 수 있습니다."}}
        button("구글 계정의 연결 권한 관리") {run {startActivity(Intent(Intent.ACTION_VIEW,Uri.parse("https://myaccount.google.com/connections")))}}
        button("구글 앱 등록 정보 확인") {run {output.text=hub.google.registration.publicRegistrationInfo()}}
        input=EditText(this).apply {hint="검색어 또는 문서 ID";isSingleLine=true};root.addView(input)
        button("실제 자료 조회로 연결 확인") {val service=selected;val query=input.text.toString();run {val result=hub.execute(testCall(service,query));output.text=(if(result.success)"조회 확인\n"else"연결 확인 필요\n")+result.content}}
        button("Google Photos에서 사진 선택") {run {require(selected==ConnectedService.PHOTOS);val uri=hub.createPhotoSelection();startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(uri)));output.text="사진 선택을 마친 뒤 이 화면으로 돌아와 선택 결과를 확인해주세요."}}
        button("선택한 Google Photos 목록 확인") {run {require(selected==ConnectedService.PHOTOS);output.text=hub.selectedPhotos()}}
        label("사진의 글자 읽기",19f)
        button("사진 선택 · 한글/영문 OCR") {startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE),OCR_REQUEST)}
        label("PC 데이터 분석 · NotebookLM",19f)
        label("선택한 자료를 등록한 본인 PC로 보내 분석합니다. PC가 켜져 있고 분석 작업기가 실행 중이어야 합니다. NotebookLM은 PC의 전용 프로필 로그인이 필요합니다.")
        button("PC 연결 파일 등록") {startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).setType("application/json").addCategory(Intent.CATEGORY_OPENABLE),PC_PAIR_REQUEST)}
        root.addView(CheckBox(this).apply {text="등록한 PC의 분석·NotebookLM 도구 사용 허용";isChecked=hub.pc.enabled();setOnCheckedChangeListener {_,checked->hub.pc.allow(checked)}})
        button("PC 도구 실제 연결 확인") {run {output.text=hub.pc.verify()}}
        button("PC 집합 에이전트 제어") { startActivity(Intent(this, PcOpenClawActivity::class.java)) }
        button("현재 대화의 분석 파일 선택") {startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION),DATA_REQUEST)}
        button("선택한 파일의 표·통계 확인") {run {val result=hub.pc.execute(ToolCall(java.util.UUID.randomUUID().toString(),"analyze_selected_data",buildJsonObject {put("mode","statistics")}));output.text=result.content}}
        button("NotebookLM 노트북 목록 조회") {run {val result=hub.pc.execute(ToolCall(java.util.UUID.randomUUID().toString(),"list_notebooklm_notebooks",buildJsonObject {}));output.text=result.content}}
        button("최근 분석 차트 보기") {run {
            val app=(application as com.user.localagent.LocalAgentApplication).container
            check(hub.store.read("pc.chart.thread")==app.runs.selectedThreadId.value)
            val uri=Uri.parse(hub.store.read("pc.chart.uri")?:error("먼저 차트를 만들어주세요."))
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri,"image/png").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        }}
        label("티맵 · 넷플릭스 · 쿠팡 · 쿠팡이츠",19f)
        val consumerApps=ConsumerApps(this)
        label(ConsumerApp.entries.joinToString(" · "){it.label+if(it in consumerApps.installed())" 설치됨"else" 설치 필요"})
        label("각 앱에 로그인된 계정을 그대로 사용합니다. 비서로 로그인 정보를 복사하지 않으며 화면 열기와 실제 재생·주문 완료를 구분합니다.")
        fun consumerAction(name:String,query:Boolean=false){run {
            val arguments=buildJsonObject {if(query){require(input.text.isNotBlank());put("query",input.text.toString())}}
            startActivity(consumerApps.prepare(ToolCall(java.util.UUID.randomUUID().toString(),name,arguments)))
            output.text="공식 앱 또는 검색 화면 열기를 요청했습니다."
        }}
        button("티맵 열기") {consumerAction("open_tmap")}
        button("입력한 장소를 티맵에서 찾기") {consumerAction("open_tmap",true)}
        button("넷플릭스 열기") {consumerAction("open_netflix")}
        button("입력한 작품을 넷플릭스에서 찾기") {consumerAction("search_netflix",true)}
        button("쿠팡 열기") {consumerAction("open_coupang")}
        button("쿠팡 구매후기 준비") {startActivity(Intent(this, CoupangReviewActivity::class.java))}
        button("입력한 상품을 쿠팡에서 찾기") {consumerAction("search_coupang",true)}
        button("쿠팡이츠 열기") {consumerAction("open_coupang_eats")}
        label("NotebookLM에 자료 전달",19f)
        label("아래 공유는 입력한 웹·YouTube 주소를 다른 앱으로 전달합니다. NotebookLM 자동 질의 연결과는 별개입니다.")
        button("입력한 주소 공유") {run {val text=input.text.toString();val uri=java.net.URI(text);require(uri.scheme=="https"&&!uri.host.isNullOrBlank()&&uri.userInfo==null);startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,text),"자료를 받을 앱 선택"))}}
        output=label("");output.setTextIsSelectable(true)
        if(consentLost)output.text="이전 Google 권한 요청이 종료됐습니다. 연결할 서비스를 확인한 뒤 다시 연결해주세요."
        button("닫기") {finish()}
        servicePicker.onItemSelectedListener=object:AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent:AdapterView<*>?,view:View?,position:Int,id:Long){refresh()}
            override fun onNothingSelected(parent:AdapterView<*>?)=Unit
        }
        refresh()
    }
    private fun refresh(){
        val service=selected
        val state=runCatching {hub.store.connection(service)}.getOrNull()
        status.text=when {state==null->"연결 전";state.verifiedAt==0L->"계정 권한 연결됨 · 실제 자료 조회 검증 전";else->"실제 조회 성공 이력 있음 · 실행할 때 권한 다시 확인"}
        connectRead.visibility=if(service.google)View.VISIBLE else View.GONE
        connectWrite.visibility=if(ConnectionCatalog.tools.values.any {it.service==service&&it.writes})View.VISIBLE else View.GONE
        token.visibility=if(service.google)View.GONE else View.VISIBLE
        chat.visibility=if(service==ConnectedService.TELEGRAM)View.VISIBLE else View.GONE
    }
    private suspend fun advanceGoogleBatch(){
        val result=googleBatch.authorize()
        output.text=googleBatch.summary()
        if(result!=null)startIntentSenderForResult(requireNotNull(result.pendingIntent).intentSender,GOOGLE_ALL_REQUEST,null,0,0,0)
    }
    private fun authorize(write:Boolean){val service=selected;run {
        check(pendingConsentToken==null){"이전 Google 권한 확인을 먼저 마쳐주세요."}
        val result=hub.google.authorize(service,write)
        if(result.hasResolution()){
            val request=GoogleConsentRequests.store.begin(service.id,write);pendingConsentToken=request.token
            try{startIntentSenderForResult(requireNotNull(result.pendingIntent).intentSender,GOOGLE_REQUEST,null,0,0,0)}
            catch(failure:Exception){GoogleConsentRequests.store.cancel(request.token);pendingConsentToken=null;throw failure}
        }
        else {withContext(Dispatchers.IO){hub.google.accept(service,write,result);hub.clearCache()};output.text="계정 권한을 연결했습니다. 실제 자료 조회로 확인해주세요."}
    }}
    @Deprecated("Activity result boundary retained for a self-contained setup screen")
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if(requestCode==GOOGLE_ALL_REQUEST){
            if(resultCode!=RESULT_OK||data==null){hub.store.write("google.batch.status","user_canceled");return}
            run {try{withContext(Dispatchers.IO){googleBatch.accept(hub.google.fromIntent(data))};advanceGoogleBatch()}catch(error:Exception){hub.store.write("google.batch.status","consent_result_error");throw error}}
            return
        }
        if(requestCode==GOOGLE_REQUEST){
            val token=pendingConsentToken
            if(resultCode!=RESULT_OK||data==null){GoogleConsentRequests.store.cancel(token);pendingConsentToken=null;output.text="Google 권한 연결을 취소했습니다.";return}
            val request=GoogleConsentRequests.store.claim(token)
            pendingConsentToken=null
            if(request==null){
                if(!GoogleConsentRequests.store.wasConsumed(token))output.text="권한 요청의 서비스·범위를 복원할 수 없습니다. 원하는 서비스를 확인한 뒤 다시 연결해주세요."
                return
            }
            val service=ConnectedService.entries.firstOrNull{it.id==request.serviceId&&it.google}
                ?:return
            run {val result=hub.google.fromIntent(data);withContext(Dispatchers.IO){hub.google.accept(service,request.write,result);hub.clearCache()};output.text="구글 계정 권한을 연결했습니다. 실제 자료 조회로 확인해주세요."}
            return
        }
        if(resultCode!=RESULT_OK||data==null)return
        when(requestCode){
            OCR_REQUEST->{val uri=data.data?:return;run {val text=LocalOcr.read(this@ConnectionsActivity,uri);output.text="${text.blockCount}개 영역 · ${text.width}×${text.height}${if(text.truncated)" · 일부 표시"else""}\n${text.text}"}}
            DATA_REQUEST->{val uri=data.data?:return;run {output.text=hub.pc.select(uri)}}
            PC_PAIR_REQUEST->{val uri=data.data?:return;run {com.user.localagent.compute.PcComputeSettings.get(this@ConnectionsActivity).importPairing(uri);output.text="PC 연결 정보를 암호화 저장했습니다. PC 도구 사용을 허용한 뒤 연결을 확인해주세요."}}
        }
    }
    override fun onSaveInstanceState(outState:Bundle){
        GoogleConsentRequests.store.peek(pendingConsentToken)?.let{request->
            outState.putString("google.consent.token",request.token)
            outState.putString("google.consent.service",request.serviceId)
            outState.putBoolean("google.consent.write",request.write)
        }
        super.onSaveInstanceState(outState)
    }
    private fun run(block:suspend ()->Unit){if(busy)return;busy=true;tasks.launch {try{block()}catch(cancel:CancellationException){throw cancel}catch(_:Exception){output.text="작업을 완료하지 못했습니다. 선택한 자료·계정 권한·PC 작업기 연결을 확인해주세요. OCR 첫 사용에는 Google Play의 인식 모듈 다운로드가 필요합니다."}finally{busy=false;refresh()}}}
    private fun testCall(service:ConnectedService,input:String):ToolCall {
        val args=linkedMapOf<String,String>()
        val name=when(service){
            ConnectedService.DRIVE->{args["query"]=input.ifBlank {"ZipHap"};"search_drive_files"}
            ConnectedService.DOCS->{args["id"]=input;"read_google_document"}
            ConnectedService.SHEETS->{args["id"]=input;"read_google_sheet"}
            ConnectedService.SLIDES->{args["id"]=input;"read_google_presentation"}
            ConnectedService.GMAIL->{args["query"]=input.ifBlank {"newer_than:7d"};"search_gmail_messages"}
            ConnectedService.CALENDAR->{val start=ZonedDateTime.now(ZoneId.of("Asia/Seoul")).toOffsetDateTime();args["start"]=start.toString();args["end"]=start.plusDays(7).toString();"list_google_calendar_events"}
            ConnectedService.TASKS->"list_google_tasks"
            ConnectedService.CONTACTS->{args["query"]=input;"search_google_contacts"}
            ConnectedService.FORMS->{args["id"]=input;"read_google_form"}
            ConnectedService.YOUTUBE->{args["query"]=input;if(input.isBlank())"list_youtube_playlists"else"search_youtube_videos"}
            ConnectedService.TELEGRAM->"list_telegram_bot_updates"
            ConnectedService.NOTION->{args["query"]=input;"search_notion_pages"}
            ConnectedService.TODOIST->"list_todoist_tasks"
            ConnectedService.PHOTOS->error("사진 선택 버튼을 사용해주세요.")
        }
        return ToolCall(java.util.UUID.randomUUID().toString(),name,buildJsonObject {args.forEach {(k,v)->put(k,v)}})
    }
    override fun onDestroy(){if(isFinishing)GoogleConsentRequests.store.cancel(pendingConsentToken);tasks.cancel();super.onDestroy()}
    companion object {private const val GOOGLE_REQUEST=7301;private const val OCR_REQUEST=7302;private const val DATA_REQUEST=7303;private const val PC_PAIR_REQUEST=7304;private const val GOOGLE_ALL_REQUEST=7305}
}
