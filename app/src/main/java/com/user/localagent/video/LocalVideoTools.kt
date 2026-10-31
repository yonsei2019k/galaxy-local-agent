package com.user.localagent.video

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import java.io.File
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

data class VideoExportProgress(val phase:String,val fraction:Float?=null,val running:Boolean=false)
data class VideoExport(val file:File,val durationMs:Long,val width:Int,val height:Int,val mimeType:String,val sizeBytes:Long)

/** Local edit/export only: no text-to-video model, network, gallery scan or automatic sharing. */
@androidx.annotation.OptIn(UnstableApi::class)
class LocalVideoTools(context:Context) {
    private val app=context.applicationContext
    private val mutex=Mutex()
    private val main=Handler(Looper.getMainLooper())
    private val mutableProgress=MutableStateFlow(VideoExportProgress("준비됨"))
    val progress:StateFlow<VideoExportProgress> = mutableProgress.asStateFlow()
    @Volatile private var activeJob:Job?=null
    @Volatile private var activeTransformer:Transformer?=null

    suspend fun trim(uri:Uri,startMs:Long,endMs:Long):VideoExport {
        require(startMs>=0&&endMs>startMs&&endMs-startMs<=MAX_DURATION_MS){"잘라낼 구간은 0초 이후, 최대60초여야 합니다."}
        requireContent(uri)
        return export(endMs-startMs){session->
            mutableProgress.value=VideoExportProgress("선택한 동영상 읽는 중",running=true)
            val input=session.file("input-video")
            copySelected(uri,input,MAX_VIDEO_INPUT_BYTES)
            val metadata=metadata(input)
            require(metadata.mimeType.startsWith("video/")&&endMs<=metadata.durationMs){"유효한 동영상 길이 안의 구간을 선택해주세요."}
            val clipping=MediaItem.ClippingConfiguration.Builder().setStartPositionMs(startMs).setEndPositionMs(endMs).build()
            val media=MediaItem.Builder().setUri(Uri.fromFile(input)).setClippingConfiguration(clipping).build()
            val item=EditedMediaItem.Builder(media).setEffects(outputEffects()).build()
            Composition.Builder(EditedMediaItemSequence.withAudioAndVideoFrom(listOf(item)))
                .setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL).build()
        }
    }

    suspend fun slideshow(uris:List<Uri>,imageDurationMs:Long=3000):VideoExport {
        require(uris.size in 1..20){"사용자가 선택한 사진1–20장이 필요합니다."}
        require(imageDurationMs in 1000..10000){"사진당 표시 시간은1–10초여야 합니다."}
        val selected=uris.toList();selected.forEach(::requireContent)
        val duration=imageDurationMs*selected.size
        require(duration<=MAX_DURATION_MS){"슬라이드쇼 전체 길이는 최대60초입니다."}
        return export(duration){session->
            var total=0L
            val items=selected.mapIndexed {index,uri->
                currentCoroutineContext().ensureActive()
                mutableProgress.value=VideoExportProgress("사진 준비 중 · ${index+1}/${selected.size}",running=true)
                val original=session.file("photo-$index-input")
                total+=copySelected(uri,original,MAX_IMAGE_INPUT_BYTES)
                require(total<=MAX_IMAGE_TOTAL_BYTES){"선택한 사진 총 용량은100MiB 이하여야 합니다."}
                val normalized=session.file("photo-$index.png")
                normalizeImage(original,normalized)
                val media=MediaItem.Builder().setUri(Uri.fromFile(normalized)).setMimeType(MimeTypes.IMAGE_PNG).setImageDurationMs(imageDurationMs).build()
                EditedMediaItem.Builder(media).setRemoveAudio(true).setDurationUs(imageDurationMs*1000).setFrameRate(30).setEffects(outputEffects()).build()
            }
            Composition.Builder(EditedMediaItemSequence.withVideoFrom(items)).build()
        }
    }

    fun cancel(){
        activeJob?.cancel(CancellationException("동영상 작업을 취소했습니다."))
        val transformer=activeTransformer
        if(transformer!=null)main.post {if(activeTransformer===transformer)transformer.cancel()}
    }

    private suspend fun export(expectedDurationMs:Long,composition:suspend (Session)->Composition):VideoExport=withContext(Dispatchers.IO){
        check(mutex.tryLock()){"이미 동영상 작업이 진행 중입니다."}
        var session:Session?=null
        var committed:File?=null
        try {
            activeJob=currentCoroutineContext()[Job]
            mutableProgress.value=VideoExportProgress("동영상 작업 준비 중",running=true)
            val actual=Session();session=actual
            val plan=composition(actual)
            val temporary=actual.file("output.mp4")
            mutableProgress.value=VideoExportProgress("MP4 인코딩 중",running=true)
            withTimeout(EXPORT_TIMEOUT_MS){runTransformer(plan,temporary)}
            currentCoroutineContext().ensureActive()
            mutableProgress.value=VideoExportProgress("MP4 결과 확인 중",running=true)
            val checked=verifyOutput(temporary,expectedDurationMs)
            val destination=File(actual.root,"video-${UUID.randomUUID()}.mp4").canonicalFile
            check(destination.parentFile==actual.root&&!destination.exists())
            check(temporary.renameTo(destination)){"검증한 동영상을 저장하지 못했습니다."}
            committed=destination
            currentCoroutineContext().ensureActive()
            val result=checked.copy(file=destination)
            mutableProgress.value=VideoExportProgress("MP4 저장·검증 완료",1f,false)
            result
        } catch(error:Throwable){
            mutableProgress.value=VideoExportProgress(if(error is CancellationException)"작업 취소됨" else "동영상 내보내기 실패")
            committed?.let {file->if(file.canonicalFile.parentFile==session?.root&&file.name.startsWith("video-"))file.delete()}
            throw error
        } finally {
            try {withContext(NonCancellable+Dispatchers.Main.immediate){activeTransformer?.cancel();activeTransformer=null}}
            finally {try {withContext(NonCancellable+Dispatchers.IO){session?.cleanup()}}
                finally {activeJob=null;mutex.unlock()}}
        }
    }

    private suspend fun runTransformer(composition:Composition,output:File)=coroutineScope {
        val finished=CompletableDeferred<ExportResult>()
        val transformer=withContext(Dispatchers.Main.immediate){
            Transformer.Builder(app).setLooper(Looper.getMainLooper()).setVideoMimeType(MimeTypes.VIDEO_H264).setAudioMimeType(MimeTypes.AUDIO_AAC)
                .addListener(object:Transformer.Listener {
                    override fun onCompleted(composition:Composition,exportResult:ExportResult){finished.complete(exportResult)}
                    override fun onError(composition:Composition,exportResult:ExportResult,exportException:ExportException){finished.completeExceptionally(exportException)}
                }).build().also {activeTransformer=it;it.start(composition,output.absolutePath)}
        }
        val polling=launch(Dispatchers.Main.immediate){
            val holder=ProgressHolder()
            while(isActive&&!finished.isCompleted){
                val status=transformer.getProgress(holder)
                mutableProgress.value=VideoExportProgress("MP4 인코딩 중",if(status==Transformer.PROGRESS_STATE_AVAILABLE)holder.progress.coerceIn(0,100)/100f else null,true)
                delay(200)
            }
        }
        try {finished.await()}finally {
            withContext(NonCancellable){polling.cancelAndJoin()}
            withContext(NonCancellable+Dispatchers.Main.immediate){transformer.cancel();if(activeTransformer===transformer)activeTransformer=null}
        }
    }

    private fun outputEffects()=Effects(emptyList(),listOf(Presentation.createForWidthAndHeight(1280,720,Presentation.LAYOUT_SCALE_TO_FIT)))
    private fun requireContent(uri:Uri){require(uri.scheme=="content"&&!uri.authority.isNullOrBlank()){"사용자가 선택한 content URI만 처리합니다."}}
    private suspend fun copySelected(uri:Uri,destination:File,limit:Long):Long {
        var total=0L
        app.contentResolver.openInputStream(uri)?.use {input->destination.outputStream().use {output->
            val buffer=ByteArray(64*1024)
            while(true){currentCoroutineContext().ensureActive();val read=input.read(buffer);if(read<0)break;total+=read;require(total<=limit){"선택한 파일이 로컬 처리 용량 제한을 초과했습니다."};output.write(buffer,0,read)}
        }}?:error("선택한 미디어 파일을 읽을 수 없습니다.")
        require(total>0){"빈 미디어 파일입니다."};return total
    }
    private fun normalizeImage(input:File,output:File){
        val bounds=BitmapFactory.Options().apply {inJustDecodeBounds=true};BitmapFactory.decodeFile(input.absolutePath,bounds)
        require(bounds.outWidth in 1..16384&&bounds.outHeight in 1..16384&&bounds.outWidth.toLong()*bounds.outHeight<=100_000_000){"지원하지 않거나 너무 큰 사진입니다."}
        var sample=1;while(max(bounds.outWidth,bounds.outHeight)/sample>2560)sample*=2
        val decoded=BitmapFactory.decodeFile(input.absolutePath,BitmapFactory.Options().apply {inSampleSize=sample;inPreferredConfig=Bitmap.Config.ARGB_8888})?:error("선택한 사진을 디코딩하지 못했습니다.")
        var oriented:Bitmap?=null;var canvasBitmap:Bitmap?=null
        try {
            val orientation=runCatching {ExifInterface(input.absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION,ExifInterface.ORIENTATION_NORMAL)}.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
            val matrix=Matrix().apply {when(orientation){
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL->setScale(-1f,1f)
                ExifInterface.ORIENTATION_ROTATE_180->setRotate(180f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL->setScale(1f,-1f)
                ExifInterface.ORIENTATION_TRANSPOSE->{setRotate(90f);postScale(-1f,1f)}
                ExifInterface.ORIENTATION_ROTATE_90->setRotate(90f)
                ExifInterface.ORIENTATION_TRANSVERSE->{setRotate(-90f);postScale(-1f,1f)}
                ExifInterface.ORIENTATION_ROTATE_270->setRotate(-90f)
            }}
            val image=if(matrix.isIdentity)decoded else Bitmap.createBitmap(decoded,0,0,decoded.width,decoded.height,matrix,true)
            oriented=image
            val rendered=Bitmap.createBitmap(1280,720,Bitmap.Config.ARGB_8888);canvasBitmap=rendered
            val canvas=Canvas(rendered);canvas.drawColor(Color.BLACK)
            val scale=min(1280f/image.width,720f/image.height);val width=image.width*scale;val height=image.height*scale
            canvas.drawBitmap(image,null,RectF((1280-width)/2,(720-height)/2,(1280+width)/2,(720+height)/2),Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
            output.outputStream().use {check(rendered.compress(Bitmap.CompressFormat.PNG,100,it)){"사진 정규화 실패"}}
        } finally {canvasBitmap?.recycle();if(oriented!==decoded)oriented?.recycle();decoded.recycle()}
    }
    private data class Metadata(val durationMs:Long,val width:Int,val height:Int,val mimeType:String)
    private fun metadata(file:File):Metadata=MediaMetadataRetriever().use {retriever->
        retriever.setDataSource(file.absolutePath)
        Metadata(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?:error("동영상 길이를 읽지 못했습니다."),retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()?:0,retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()?:0,retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE).orEmpty()).also {require(it.durationMs>0&&it.width>0&&it.height>0){"유효한 비디오 트랙이 없습니다."}}
    }
    private fun verifyOutput(file:File,expected:Long):VideoExport {
        require(file.isFile&&file.length() in 1..MAX_OUTPUT_BYTES){"출력 MP4 파일을 확인하지 못했습니다."}
        val header=ByteArray(12);file.inputStream().use {require(it.read(header)==header.size)}
        require(header.copyOfRange(4,8).toString(Charsets.US_ASCII)=="ftyp"){"실제 MP4 컨테이너가 아닙니다."}
        val actual=metadata(file)
        require(actual.mimeType=="video/mp4"&&actual.width<=1280&&actual.height<=720&&actual.durationMs<=MAX_DURATION_MS&&abs(actual.durationMs-expected)<=750){"MP4 길이·해상도 검증이 실패했습니다."}
        MediaMetadataRetriever().use {retriever->retriever.setDataSource(file.absolutePath);val frame=retriever.getFrameAtTime(0,MediaMetadataRetriever.OPTION_CLOSEST)?:error("MP4 프레임을 읽지 못했습니다.");frame.recycle()}
        return VideoExport(file,actual.durationMs,actual.width,actual.height,actual.mimeType,file.length())
    }
    private inner class Session {
        val root=File(app.filesDir,"video-exports").canonicalFile
        val directory:File
        private val owned=mutableListOf<File>()
        init {check(root.parentFile==app.filesDir.canonicalFile);check(root.isDirectory||root.mkdir());directory=File(root,".work-${UUID.randomUUID()}").canonicalFile;check(directory.parentFile==root&&directory.mkdir())}
        fun file(name:String):File {require(Regex("[a-z0-9.-]+").matches(name));return File(directory,name).canonicalFile.also {check(it.parentFile==directory&&!it.exists()&&it !in owned);owned+=it}}
        fun cleanup(){owned.asReversed().forEach {file->check(file.canonicalFile.parentFile==directory);check(!file.exists()||file.delete()){"작업 임시 파일 정리에 실패했습니다."}};check(directory.canonicalFile.parentFile==root&&directory.name.startsWith(".work-"));check(directory.listFiles()?.isEmpty()==true&&directory.delete()){"작업 임시 폴더 정리에 실패했습니다."}}
    }
    companion object {
        const val MAX_DURATION_MS=60000L
        private const val MAX_VIDEO_INPUT_BYTES=512L*1024*1024
        private const val MAX_IMAGE_INPUT_BYTES=20L*1024*1024
        private const val MAX_IMAGE_TOTAL_BYTES=100L*1024*1024
        private const val MAX_OUTPUT_BYTES=256L*1024*1024
        private const val EXPORT_TIMEOUT_MS=300000L
    }
}
