package com.user.localagent

import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import com.user.localagent.connections.ConnectionHub
import com.user.localagent.connections.ConsumerApps
import com.user.localagent.core.ToolCall
import com.user.localagent.core.connections.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class ConnectedServiceDeviceValidation {
    @Test fun authorizedMetadataEndpointsAndInstalledAppHandlers()=runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("connectionReadVerificationApproved")=="true")
        val context=instrumentation.targetContext
        val hub=ConnectionHub.get(context)
        assertTrue(GoogleAuthorizationPlan.services.all {hub.store.connection(it)!=null})
        val probes=linkedMapOf(
            ConnectedService.DRIVE to "https://www.googleapis.com/drive/v3/about?fields=kind",
            ConnectedService.GMAIL to "https://gmail.googleapis.com/gmail/v1/users/me/messages?maxResults=1&fields=resultSizeEstimate",
            ConnectedService.CALENDAR to "https://www.googleapis.com/calendar/v3/calendars/primary/events?timeMin=2099-01-01T00%3A00%3A00Z&timeMax=2099-01-02T00%3A00%3A00Z&maxResults=1&fields=kind",
            ConnectedService.TASKS to "https://tasks.googleapis.com/tasks/v1/users/@me/lists?maxResults=1&fields=kind",
            ConnectedService.CONTACTS to "https://people.googleapis.com/v1/people/me/connections?personFields=names&pageSize=1&fields=totalPeople",
            ConnectedService.YOUTUBE to "https://www.googleapis.com/youtube/v3/playlists?part=id&mine=true&maxResults=1&fields=kind"
        )
        val statuses=linkedMapOf<String,Int>()
        for((service,url)in probes){
            val connection=hub.store.connection(service)!!
            val token=hub.google.token(service,connection.writeAllowed)
            val response=hub.transport(service,ApiRequest(url),token)
            statuses[service.id]=response.status
            if(response.status in 200..299)hub.store.connect(service,connection.copy(verifiedAt=System.currentTimeMillis()))
        }
        var photoCleanup=false
        val photoToken=hub.google.token(ConnectedService.PHOTOS,false)
        val selection=hub.transport(ConnectedService.PHOTOS,ApiRequest("https://photospicker.googleapis.com/v1/sessions","POST","{}"),photoToken)
        statuses["photos"]=selection.status
        if(selection.status in 200..299){
            val id=Json.parseToJsonElement(selection.body).jsonObject.getValue("id").jsonPrimitive.content
            try {
                val response=hub.transport(ConnectedService.PHOTOS,ApiRequest("https://photospicker.googleapis.com/v1/sessions/${ConnectionRequests.key(id)}"),photoToken)
                statuses["photos_session_read"]=response.status
                if(response.status in 200..299)hub.store.connection(ConnectedService.PHOTOS)?.let {hub.store.connect(ConnectedService.PHOTOS,it.copy(verifiedAt=System.currentTimeMillis()))}
            }finally{
                // Test-only cleanup; production ApiRequest deliberately excludes DELETE.
                val cleanup=java.net.URL("https://photospicker.googleapis.com/v1/sessions/${ConnectionRequests.key(id)}").openConnection() as javax.net.ssl.HttpsURLConnection
                try {
                    cleanup.requestMethod="DELETE";cleanup.instanceFollowRedirects=false
                    cleanup.connectTimeout=10_000;cleanup.readTimeout=10_000
                    cleanup.setRequestProperty("Authorization","Bearer $photoToken")
                    photoCleanup=cleanup.responseCode in 200..299
                }finally{cleanup.disconnect()}
            }
        }
        val apps=ConsumerApps(context)
        val installed=apps.installed()
        val handlers=linkedMapOf<String,String>()
        for(name in listOf("open_tmap","open_netflix","open_coupang","open_coupang_eats","search_coupang","open_netflix_title")){
            val arguments=buildJsonObject {if(name=="search_coupang")put("query","ZipHap connection verification");if(name=="open_netflix_title")put("id","80057281")}
            val call=ToolCall(UUID.randomUUID().toString(),name,arguments)
            val intent=apps.prepare(call)
            val component=intent.resolveActivity(context.packageManager)
            assertNotNull(component)
            handlers[name]=if(component!!.packageName==ConsumerAppCatalog.appForTool.getValue(name).packageName)"native_app"else"browser"
        }
        val report=buildJsonObject {
            put("httpStatus",buildJsonObject {statuses.forEach {(name,status)->put(name,status)}})
            put("installedApps",buildJsonObject {ConsumerApp.entries.forEach {put(it.id,it in installed)}})
            put("intentHandlers",buildJsonObject {handlers.forEach {(name,handler)->put(name,handler)}})
            put("photosTestSessionDeleted",photoCleanup);put("privateDocumentContentsRead",false)
            put("remainingResourceSelection",JsonArray(listOf("docs","sheets","slides","forms").map(::JsonPrimitive)))
        }
        val output=File(context.getExternalFilesDir(null),"diagnostics/connected-service-read-verification.json")
        output.parentFile!!.mkdirs();output.writeText(report.toString())
        instrumentation.sendStatus(0,Bundle().apply {putString("stream",report.toString()+"\n")})
        assertTrue("An authorized API metadata endpoint failed",statuses.values.all {it in 200..299})
        assertTrue(photoCleanup);assertEquals(4,installed.size)
    }
}
