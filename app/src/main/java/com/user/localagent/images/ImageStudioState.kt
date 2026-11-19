package com.user.localagent.images

enum class ImageOutputOrigin { CURRENT, PREVIOUS, RESTORED }

data class ImageOutputDetails(val seed:Long?,val sourceModelId:String?,val computeLocation:String,val mode:String,
    val referenceSha256:String?,val referenceWidth:Int?,val referenceHeight:Int?,val strength:Float?)

data class ImageStudioState(val phase:String="만들고 싶은 장면을 적어주세요",val running:Boolean=false,val progress:Float=0f,
    val step:Int=0,val totalSteps:Int=0,val outputPath:String?=null,val seed:Long?=null,val error:String?=null,val prompt:String="",
    val mode:String="txt2img",val referenceSha256:String?=null,val referenceWidth:Int?=null,val referenceHeight:Int?=null,val strength:Float?=null,
    val computeLocation:String="phone",val sourceModelId:String?=null,val canResumePc:Boolean=false,val archivedPcCount:Int=0,
    val outputOrigin:ImageOutputOrigin=ImageOutputOrigin.CURRENT,val outputDetails:ImageOutputDetails?=null,
    val canPausePc:Boolean=false,val pauseRequested:Boolean=false) {
    private fun resultDetails()=ImageOutputDetails(seed,sourceModelId,computeLocation,mode,referenceSha256,referenceWidth,referenceHeight,strength)
    val displayedOutput:ImageOutputDetails? get()=if(outputPath==null)null else outputDetails?:resultDetails()
    val outputTitle:String get()=when(outputOrigin){
        ImageOutputOrigin.PREVIOUS->"이전 작업의 결과"
        ImageOutputOrigin.RESTORED->"최근 완성한 이미지"
        ImageOutputOrigin.CURRENT->"완성한 이미지"
    }
    fun beginAttempt()=copy(progress=0f,step=0,outputOrigin=if(outputPath==null)ImageOutputOrigin.CURRENT else ImageOutputOrigin.PREVIOUS,
        outputDetails=displayedOutput,canPausePc=false,pauseRequested=false)
    /** Called only after the image and its metadata have been saved and read back. */
    fun savedOutput(restored:Boolean=false)=copy(outputOrigin=if(restored)ImageOutputOrigin.RESTORED else ImageOutputOrigin.CURRENT,
        outputDetails=if(outputPath==null)null else resultDetails())
}
