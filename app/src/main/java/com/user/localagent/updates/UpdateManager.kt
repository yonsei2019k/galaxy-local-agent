package com.user.localagent.updates

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.*
import com.user.localagent.BuildConfig
import com.user.localagent.R
import com.user.localagent.core.UpdateManifest
import com.user.localagent.core.UpdateVerifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.TimeUnit

data class UpdateState(
    val message:String="업데이트 확인 전",
    val version:Long?=null,
    val downloaded:Boolean=false,
    val pendingApkSha256:String?=null,
    val pendingManifestDigest:String?=null,
    val pendingCapabilities:List<String> = emptyList(),
    val pendingPermissions:List<String> = emptyList()
)

private data class PendingUpdateApproval(
    val apkSha256:String,
    val version:Long,
    val manifestDigest:String,
    val capabilities:Set<String>,
    val newCapabilities:Set<String>,
    val newPermissions:Set<String>
) {
    fun sameAs(other:PendingUpdateApproval)=apkSha256.equals(other.apkSha256,true)&&version==other.version&&
        manifestDigest==other.manifestDigest&&capabilities==other.capabilities&&newCapabilities==other.newCapabilities&&newPermissions==other.newPermissions
}

class UpdateManager(private val context:Context) {
    private val preferences=context.getSharedPreferences("updates",Context.MODE_PRIVATE)
    private val mutableState=MutableStateFlow(UpdateState(preferences.getString("status",null)?:"업데이트 확인 전"))
    val state:StateFlow<UpdateState> = mutableState
    @Volatile private var pendingApproval:PendingUpdateApproval?=null
    private fun status(message:String,version:Long?=null,downloaded:Boolean=false,pending:PendingUpdateApproval?=null) {
        pendingApproval=pending
        mutableState.value=UpdateState(message,version,downloaded,pending?.apkSha256,pending?.manifestDigest,
            pending?.newCapabilities.orEmpty().sorted(),pending?.newPermissions.orEmpty().sorted())
        preferences.edit().putString("status",message).apply()
    }

    fun schedule() {
        val request=PeriodicWorkRequestBuilder<UpdateWorker>(15,TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,TimeUnit.MINUTES).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("trusted-release-updates",ExistingPeriodicWorkPolicy.KEEP,request)
    }
    fun sourcePermissionIntent()=Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,android.net.Uri.parse("package:${context.packageName}"))

    suspend fun checkAndApply(install:Boolean=true,retryOnIoFailure:Boolean=false):String = checkAndApplyInternal(install,retryOnIoFailure,null)

    /** Approves only the exact pending manifest and APK the user reviewed in the panel. */
    suspend fun approvePendingUpdate(apkSha256:String,version:Long,manifestDigest:String):String {
        val pending=pendingApproval?:error("승인 대기 중인 업데이트가 없습니다.")
        val visible=mutableState.value
        require(visible.pendingApkSha256==apkSha256&&visible.version==version&&visible.pendingManifestDigest==manifestDigest&&
            pending.apkSha256==apkSha256&&pending.version==version&&pending.manifestDigest==manifestDigest) {
            "승인 대기 중인 업데이트가 변경되었습니다. 내용을 다시 확인해주세요."
        }
        return checkAndApplyInternal(true,false,pending)
    }

    private suspend fun checkAndApplyInternal(install:Boolean,retryOnIoFailure:Boolean,approval:PendingUpdateApproval?):String = updateMutex.withLock { withContext(Dispatchers.IO) {
        try {
            if(install && (com.user.localagent.benchmark.RuntimeGate.inference.get()||com.user.localagent.benchmark.RuntimeGate.benchmark.get())){
                status("현재 로컬 작업이 끝난 뒤 업데이트를 확인합니다.")
                return@withContext mutableState.value.message
            }
            val trust=Json.parseToJsonElement(context.assets.open("update-trust.json").bufferedReader().use {it.readText()}).jsonObject
            val keys=trust["keys"]!!.jsonObject.mapValues { Base64.getDecoder().decode(it.value.jsonPrimitive.content) }
            if(keys.isEmpty()) {status("서명 키 설정 후 자동 패치를 사용할 수 있습니다.");return@withContext mutableState.value.message}
            val cert=trust["apkCertificateSha256"]!!.jsonPrimitive.content
            val capabilities=trust["approvedCapabilities"]?.jsonArray?.map {it.jsonPrimitive.content}?.toSet()?:emptySet()
            val verifier=UpdateVerifier(keys,cert,context.packageName,"https://github.com/yonsei2019k/galaxy-local-agent/releases/download/")
            status("GitHub 릴리스 확인 중")
            val raw=readHttps(BuildConfig.UPDATE_URL,128*1024).toString(Charsets.UTF_8)
            val verified=verifier.verify(raw,BuildConfig.VERSION_CODE.toLong()-1,capabilities)
            val manifest=verified.manifest
            if(manifest.versionCode==BuildConfig.VERSION_CODE.toLong()){status("최신 버전입니다.",manifest.versionCode);return@withContext mutableState.value.message}
            if(manifest.minSdk>Build.VERSION.SDK_INT) error("현재 Android에서 지원하지 않는 업데이트입니다.")
            val manifestDigest=sha256(raw.toByteArray(Charsets.UTF_8))
            val output=File(context.cacheDir,"updates").apply {mkdirs()}.resolve("${manifest.versionCode}.apk")
            if(!output.exists() || sha256(output)!=manifest.apkSha256.lowercase()) {
                status("업데이트 다운로드 중",manifest.versionCode)
                val temporary=File(output.path+".part")
                openHttps(manifest.apkUrl).let { connection ->
                    try {connection.inputStream.use {input ->temporary.outputStream().use {out ->
                        val buffer=ByteArray(64*1024);var bytes=0L
                        while(true){val count=input.read(buffer);if(count<0)break;bytes+=count;check(bytes<=512L*1024*1024){"업데이트 파일이 제한보다 큽니다."};out.write(buffer,0,count)}
                    }}}finally{connection.disconnect()}
                }
                check(sha256(temporary)==manifest.apkSha256.lowercase()){ "APK 파일 해시가 일치하지 않습니다." }
                check(temporary.renameTo(output)){"업데이트 파일을 확정하지 못했습니다."}
            }
            val info=context.packageManager.getPackageArchiveInfo(output.path,PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_PERMISSIONS)?:error("APK를 읽을 수 없습니다.")
            check(info.packageName==context.packageName && info.longVersionCode==manifest.versionCode){"APK 식별 정보가 일치하지 않습니다."}
            val certificates=info.signingInfo?.apkContentsSigners.orEmpty().map {MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).joinToString(""){b->"%02x".format(b)}}
            check(certificates.size==1 && certificates.single().equals(cert,true)){"신뢰한 APK 서명이 아닙니다."}
            val installedPermissions=context.packageManager.getPackageInfo(context.packageName,PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty().toSet()
            val requestedPermissions=info.requestedPermissions.orEmpty().toSet()
            val newPermissions=(requestedPermissions-installedPermissions).toSortedSet()
            val newCapabilities=manifest.capabilities.filter {it !in capabilities}.toSortedSet()
            val needsApproval=verified.requiresCapabilityApproval||newPermissions.isNotEmpty()
            val candidate=PendingUpdateApproval(manifest.apkSha256.lowercase(),manifest.versionCode,manifestDigest,
                manifest.capabilities.toSet(),newCapabilities,newPermissions)
            if(needsApproval){
                if(approval==null){
                    status("새 기능 또는 Android 권한이 포함된 업데이트입니다. 변경 내용을 확인한 뒤 설치를 승인해주세요.",manifest.versionCode,true,candidate)
                    return@withContext mutableState.value.message
                }
                if(!approval.sameAs(candidate)){
                    status("서명된 업데이트가 바뀌었습니다. 새 변경 내용을 다시 검토해주세요.",manifest.versionCode,true,candidate)
                    return@withContext mutableState.value.message
                }
                val approvedVerifier=verifier.verify(raw,BuildConfig.VERSION_CODE.toLong(),capabilities+approval.capabilities)
                check(!approvedVerifier.requiresCapabilityApproval){"업데이트 기능 범위가 승인한 내용과 다릅니다."}
            }else if(approval!=null){
                status("승인 대기 중인 업데이트가 변경되었습니다. 다시 확인해주세요.",manifest.versionCode,true)
                return@withContext mutableState.value.message
            }
            status("다운로드·서명 검증 완료",manifest.versionCode,true)
            if(!install) return@withContext mutableState.value.message
            if(!context.packageManager.canRequestPackageInstalls()){status("앱의 업데이트 설치 허용이 필요합니다.",manifest.versionCode,true);return@withContext mutableState.value.message}
            if(preferences.getBoolean("busy",false)||com.user.localagent.benchmark.RuntimeGate.inference.get()||com.user.localagent.benchmark.RuntimeGate.benchmark.get()){status("현재 작업이 끝난 뒤 업데이트합니다.",manifest.versionCode,true);return@withContext mutableState.value.message}
            val installer=context.packageManager.packageInstaller
            val params=PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(context.packageName);setSize(output.length());setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
            val sessionId=installer.createSession(params)
            installer.openSession(sessionId).use {session ->
                session.openWrite("base.apk",0,output.length()).use {out -> output.inputStream().use {it.copyTo(out)};session.fsync(out)}
                val result=Intent(context,InstallResultReceiver::class.java).setAction("com.user.localagent.UPDATE_RESULT")
                val pending=PendingIntent.getBroadcast(context,sessionId,result,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
                // Carry only existing builtin note/reminder scopes to this exact verified APK.
                // Publisher signature, payload, permission review and file hash have already passed.
                com.user.localagent.data.LocalRepository(context).carryBuiltinGrants(sha256(File(context.applicationInfo.sourceDir)),manifest.apkSha256.lowercase())
                session.commit(pending.intentSender)
            }
            status("업데이트 설치를 요청했습니다.",manifest.versionCode,true)
            mutableState.value.message
        } catch(cancel:CancellationException) {
            throw cancel
        } catch(error:IOException) {
            status("업데이트 확인: ${error.message?:error.javaClass.simpleName}")
            if(retryOnIoFailure) throw error
            mutableState.value.message
        } catch(error:Throwable) {
            val message=error.message?:error.javaClass.simpleName
            status("업데이트 확인: $message")
            mutableState.value.message
        }
    }}
    fun setBusy(busy:Boolean){preferences.edit().putBoolean("busy",busy).apply()}
    companion object {
        private val updateMutex=Mutex()
        private val downloadHosts=setOf("github.com","objects.githubusercontent.com","release-assets.githubusercontent.com","raw.githubusercontent.com")
        private fun openHttps(url:String):HttpURLConnection {
            var next=URI(url)
            repeat(6) {
                require(next.isAbsolute && next.scheme=="https" && next.host in downloadHosts && next.rawUserInfo==null && (next.port==-1 || next.port==443) && next.rawFragment==null){"허용되지 않은 업데이트 주소입니다."}
                val connection=next.toURL().openConnection() as HttpURLConnection
                connection.instanceFollowRedirects=false;connection.connectTimeout=15_000;connection.readTimeout=30_000
                connection.setRequestProperty("User-Agent","GalaxyLocalAgent/${BuildConfig.VERSION_NAME}")
                when(connection.responseCode){301,302,303,307,308->{val location=connection.getHeaderField("Location")?:error("리디렉션 주소가 없습니다.");connection.disconnect();next=next.resolve(location)};200->return connection;404->{connection.disconnect();error("아직 게시된 릴리스가 없습니다.")};429,500,502,503,504->{val code=connection.responseCode;connection.disconnect();throw IOException("일시적 다운로드 응답 $code")};else->{val code=connection.responseCode;connection.disconnect();error("다운로드 응답 $code")}}
            }
            error("업데이트 리디렉션이 너무 많습니다.")
        }
        private fun readHttps(url:String,max:Int):ByteArray {
            val connection=openHttps(url)
            try{return connection.inputStream.use {input->
                val output=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
                while(true){val count=input.read(buffer);if(count<0)break;check(output.size()+count<=max){"업데이트 정보가 너무 큽니다."};output.write(buffer,0,count)}
                output.toByteArray()
            }}finally{connection.disconnect()}
        }
        private fun sha256(file:File):String {val digest=MessageDigest.getInstance("SHA-256");file.inputStream().use {input->val buffer=ByteArray(64*1024);while(true){val count=input.read(buffer);if(count<0)break;digest.update(buffer,0,count)}};return digest.digest().joinToString(""){"%02x".format(it)}}
        private fun sha256(bytes:ByteArray):String=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
    }
}

class UpdateWorker(context:Context,parameters:WorkerParameters):CoroutineWorker(context,parameters) {
    override suspend fun doWork():Result = try {
        UpdateManager(applicationContext).checkAndApply(retryOnIoFailure=true)
        Result.success()
    } catch(cancel:CancellationException) {
        throw cancel
    } catch(_:IOException) {
        Result.retry()
    } catch(_:Throwable) {
        Result.failure()
    }
}
class InstallResultReceiver:BroadcastReceiver() {
    override fun onReceive(context:Context,intent:Intent) {
        val status=intent.getIntExtra(PackageInstaller.EXTRA_STATUS,PackageInstaller.STATUS_FAILURE)
        val preferences=context.getSharedPreferences("updates",Context.MODE_PRIVATE)
        if(status==PackageInstaller.STATUS_PENDING_USER_ACTION){
            val confirmation=intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)?:return
            val manager=context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel("updates","앱 업데이트",NotificationManager.IMPORTANCE_DEFAULT))
            val pending=PendingIntent.getActivity(context,901,confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            if(ContextCompat.checkSelfPermission(context,Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED){manager.notify(901,NotificationCompat.Builder(context,"updates").setSmallIcon(R.drawable.ic_agent).setContentTitle("업데이트 설치 확인").setContentText("Android가 설치 확인을 요청했습니다.").setContentIntent(pending).setAutoCancel(true).build())}
            preferences.edit().putString("status","Android 설치 확인이 필요합니다. 알림을 열어주세요.").apply()
        } else preferences.edit().putString("status",if(status==PackageInstaller.STATUS_SUCCESS)"업데이트 설치 완료" else "업데이트 설치 실패 · 기존 앱을 유지합니다.").apply()
    }
}
