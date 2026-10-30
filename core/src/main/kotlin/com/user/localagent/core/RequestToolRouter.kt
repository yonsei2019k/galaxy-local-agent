package com.user.localagent.core

import java.util.Locale

/** Chooses request-scoped capabilities; quoted document content never grants tool authority. */
object RequestToolRouter {
    fun select(text:String,previousUserMessages:List<String> = emptyList()):List<String> {
        val current=normalize(text.take(6000))
        if(current.isBlank()||isDocument(current))return emptyList()
        val inherit=isFollowup(current)
        // A document wrapper stays data-only even when followed by a terse "continue".
        val previous=previousUserMessages.takeLast(2).map {normalize(it.take(500))}
        if(inherit&&previous.lastOrNull()?.let(::isDocument)==true)return emptyList()
        val routing=if(inherit)(listOf(current)+previous.filterNot(::isDocument)).joinToString("\n")else current
        fun has(vararg terms:String)=terms.any {routing.contains(it)}
        fun currentHas(vararg terms:String)=terms.any {current.contains(it)}
        val imageSubject=has("이미지","사진","일러스트","그림","포스터","썸네일","배경화면","로고","아이콘")
        val draw=has("그려","그리기","그려주","그려봐")
        val imageAction=has("생성","만들","그려","그리기")
        val technicalBuild=Regex("(?:프로그램|플러그인|기능|코드|스킬|도구).{0,10}(?:만들|개발|구현|설치|업그레이드)").containsMatchIn(current)
        val analysisFollowup=inherit&&currentHas("분석","설명","요약","읽어","인식")&&!currentHas("생성","만들","그려","그리기")
        // Scene descriptions can mention calendars, notes or messages without requesting those actions.
        if((imageSubject||draw)&&imageAction&&!technicalBuild&&!analysisFollowup)return listOf("generate_image")
        if(analysisFollowup&&imageSubject)return emptyList()

        val tools=linkedSetOf<String>()
        val namedMessenger=has("메디통","카카오","카톡","텔레그램")
        val explicitOwnHistory=has("우리 대화","우리대화","너와","너랑","비서 대화","집합에이전트","집합 에이전트","ziphap")
        if(explicitOwnHistory||(!namedMessenger&&has("대화 로그","대화로그","대화 기록","대화기록")))tools.add("search_conversation_history")
        if(has("메디통","카카오","카톡","텔레그램","메신저","메시지","대화 자료","대화자료")){
            tools.addAll(listOf("list_imported_conversations","search_conversation_imports","list_messenger_notifications"))
            if(has("답장","회신","보내"))tools.add("reply_messenger_notification")
        }
        if(has("문자","sms")||(has("모든","전체")&&has("메시지")))tools.add("get_sms_summary")
        if(has("통화","전화내역","전화 내역"))tools.add("get_call_log_summary")
        if(has("녹음","음성 파일","음성파일","전사"))tools.addAll(listOf("list_audio_recordings","search_audio_transcripts"))
        if(has("일정","회의","약속","캘린더","스케줄","스케쥴"))tools.addAll(listOf("get_current_datetime","list_calendar_events","prepare_calendar_event","create_reminder"))
        if(has("메모"))tools.addAll(listOf("save_note","search_notes"))
        if(has("기억","잊어"))tools.addAll(listOf("save_memory","search_memories","delete_memory"))
        if(has("문자","sms"))tools.addAll(listOf("search_contacts","prepare_sms"))
        if(has("메일","이메일"))tools.add("prepare_email")
        if(has("지도","길찾기"))tools.add("open_map")
        if(has("알람","리마인더","알려줘"))tools.addAll(listOf("get_current_datetime","create_reminder","create_alarm"))
        if(has("웹","http","주소 열기"))tools.add("open_url")
        return tools.take(10)
    }

    private fun normalize(text:String)=text.trim().lowercase(Locale.ROOT)
    private fun isDocument(text:String)=text.replace(Regex("\\s+"),"").startsWith("선택한문서")
    private val explicitSubjects=listOf("문자","sms","메시지","메신저","카카오","카톡","텔레그램","메디통","대화","ziphap","집합에이전트",
        "메모","기억","일정","회의","약속","캘린더","스케줄","스케쥴","사진","이미지","그림","일러스트","포스터","썸네일","배경화면","로고","아이콘",
        "통화","전화","녹음","음성","전사","메일","알람","리마인더","지도","길찾기","웹","http","시간","날짜","몇 시","몇시")
    private val followupMarker=Regex("분석|진행|계속|정리|확인|조회|처리|생성|만들|그려|해줘|해봐|해주세요|응|그래|네|좋아")
    private val followupWords=Regex(listOf("그내용도","그내용","그것들도","그것들","그것도","그것","이것도","이것","그걸로","그걸","이걸","그대로","그렇게",
        "해주시겠어요","해주실래요","해줄래","해보자","해봐주세요","해주세요","해줘요","해줘","해봐","해라","하자","해","그냥","부탁해요","부탁해","진행","계속","이어서","이어","분석","정리","확인","조회","처리",
        "생성","만들어줘","만들어주세요","그려줘","그려주세요","만들어","그려","모든걸","모든것","모든","전체","전부","모두","다시","자세히","구체적으로","상세히","더","좀","조금","다","응","그래","네","좋아","맞아",
        "작년","지난해","올해","내년","지난달","이번달","이번주","지난주","오늘","어제","이번","지난","동안","기간","연도","부터","까지","치","도","요").sortedByDescending {it.length}.joinToString("|"){Regex.escape(it)})
    private fun isFollowup(current:String):Boolean {
        if(current.length>100||explicitSubjects.any {current.contains(it)}||!followupMarker.containsMatchIn(current))return false
        // After generic follow-up/period words, a remaining topic means a new request.
        val remainder=current.replace(Regex("\\d{1,4}\\s*(?:년|월|일|주|분기|시간|시)"),"")
            .replace(Regex("\\s+"),"").replace(followupWords,"")
        return remainder.none {it.isLetterOrDigit()}
    }
}
