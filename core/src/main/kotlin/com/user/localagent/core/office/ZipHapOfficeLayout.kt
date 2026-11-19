package com.user.localagent.core.office

enum class OfficeSeat(val displayName:String,val jobTitle:String) {
    CHAIR("이수빈","회장"),
    DIRECTOR("김호영","본부장"),
    ASSOCIATE_GYUMIN("김규민","대리"),
    ASSOCIATE_SEOYUL("김서율","대리");

    val label:String get()="$jobTitle $displayName"
}

/** Fixed product rooms. Work topics belong to tasks inside a room. */
enum class ZipHapRoom(val label:String,val agent:OfficeSeat?) {
    MAIN("메인 대화",OfficeSeat.CHAIR),
    OFFICE("사무실",null),
    DIRECTOR(OfficeSeat.DIRECTOR.label,OfficeSeat.DIRECTOR),
    ASSOCIATE_GYUMIN(OfficeSeat.ASSOCIATE_GYUMIN.label,OfficeSeat.ASSOCIATE_GYUMIN),
    ASSOCIATE_SEOYUL(OfficeSeat.ASSOCIATE_SEOYUL.label,OfficeSeat.ASSOCIATE_SEOYUL)
}

/** Routing metadata only. The host must authenticate the seat and authorize each execution separately. */
object ZipHapOfficeLayout {
    private val employees=OfficeSeat.entries.filter {it!=OfficeSeat.CHAIR}.toSet()

    fun assignmentRecipients(sender:OfficeSeat,recipients:Set<OfficeSeat>):Set<OfficeSeat> {
        require(sender==OfficeSeat.CHAIR&&recipients.isNotEmpty()&&recipients.all{it in employees})
        return recipients.toSet()
    }

    fun resultRecipient(sender:OfficeSeat):OfficeSeat {
        require(sender in employees)
        return OfficeSeat.CHAIR
    }

    fun mayPublishMainSummary(sender:OfficeSeat):Boolean=sender==OfficeSeat.CHAIR
}
