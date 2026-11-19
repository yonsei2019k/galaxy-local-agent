package com.user.localagent.core.office

/** Host-owned routing. A report conveys data; it cannot grant tools or establish an external effect. */
class OfficeWork private constructor(
    val id:String,
    val instruction:String,
    val recipients:Set<OfficeSeat>,
    val reports:Map<OfficeSeat,Report>,
) {
    data class Report(val runId:String,val summary:String,val succeeded:Boolean)

    fun receive(workId:String,sender:OfficeSeat,report:Report):OfficeWork {
        require(workId==id&&sender in recipients)
        officeOpaqueId(report.runId)
        officeText(report.summary,1200)
        reports[sender]?.let{check(it==report);return this}
        check(reports.values.none{it.runId==report.runId})
        return OfficeWork(id,instruction,recipients,java.util.Collections.unmodifiableMap(reports+(sender to report)))
    }

    /** The chair receives all reports, including failures, before composing a final response. */
    fun collect(sender:OfficeSeat):Map<OfficeSeat,Report> {
        check(ZipHapOfficeLayout.mayPublishMainSummary(sender)&&reports.keys==recipients)
        return reports
    }

    companion object {
        fun assign(id:String,sender:OfficeSeat,instruction:String,recipients:Set<OfficeSeat>):OfficeWork {
            officeEntityId(id)
            officeText(instruction,1200)
            val selected=ZipHapOfficeLayout.assignmentRecipients(sender,recipients)
            return OfficeWork(id,instruction,java.util.Collections.unmodifiableSet(selected),emptyMap())
        }
    }
}
