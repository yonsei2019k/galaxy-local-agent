package com.user.localagent.images

data class ImageStudioState(val phase:String="만들고 싶은 장면을 적어주세요",val running:Boolean=false,val progress:Float=0f,
    val step:Int=0,val totalSteps:Int=0,val outputPath:String?=null,val seed:Long?=null,val error:String?=null,val prompt:String="",
    val mode:String="txt2img",val referenceSha256:String?=null,val referenceWidth:Int?=null,val referenceHeight:Int?=null,val strength:Float?=null,
    val computeLocation:String="phone",val sourceModelId:String?=null)
