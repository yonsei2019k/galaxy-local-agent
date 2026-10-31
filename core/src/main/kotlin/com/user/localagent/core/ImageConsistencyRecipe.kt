package com.user.localagent.core

import kotlinx.serialization.Serializable

/** Owner-authored generation data, never instruction authority or an identity guarantee. */
@Serializable
data class ImageConsistencyRecipe(
    val title:String="나의 이미지 시리즈",
    val preserve:String="",
    val scene:String="",
    val negative:String="",
    val seed:Long=42,
    val strength:Float=.45f,
    val phoneSteps:Int=40,
    val pcSteps:Int=50,
    val referenceName:String?=null,
    val referenceSha256:String?=null,
    val pcModelId:String=PcImageModelCatalog.QUALITY_ID
) {
    fun validate():ImageConsistencyRecipe=also {
        require(title.isNotBlank()&&title.length<=80)
        require(preserve.length<=700&&scene.length<=1200&&negative.length<=1000)
        require(seed>=0&&strength.isFinite()&&strength in .4f.. .85f)
        require(phoneSteps in 1..50&&pcSteps in 1..50)
        require((referenceName==null)==(referenceSha256==null))
        referenceName?.let {require(it.matches(Regex("[a-f0-9]{64}\\.image"))&&it=="$referenceSha256.image")}
        PcImageModelCatalog.selection(pcModelId)
        require(prompt().length<=2000)
    }
    fun prompt():String=if(preserve.isBlank())scene.trim() else
        "Preserve these characteristics of the reference: ${preserve.trim()}\nRequested scene or change: ${scene.trim()}"
}
