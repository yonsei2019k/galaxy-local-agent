package com.user.localagent.core

import kotlinx.serialization.json.*

data class ChatViewportAnchor(val messageId:String,val blockIndex:Int)

/** A local UI anchor, never a URI, instruction or execution capability. */
object ChatViewportAnchorCodec {
    private val identity=Regex("[a-zA-Z0-9_-]{1,100}")
    private val keys=setOf("v","messageId","blockIndex")
    fun encode(messageId:String,blockIndex:Int):String {
        validate(messageId,blockIndex)
        return buildJsonObject {put("v",1);put("messageId",messageId);put("blockIndex",blockIndex)}.toString()
    }
    fun decode(token:String):ChatViewportAnchor {
        require(token.length in 1..256&&token.none{it.code<32||it.code==127}){"화면 위치의 저장 형식이 올바르지 않습니다."}
        if(identity.matches(token))return ChatViewportAnchor(token,0)
        val data=Json.parseToJsonElement(token).jsonObject
        require(data.keys==keys&&data["v"]?.jsonPrimitive?.isString==false&&data["v"]?.jsonPrimitive?.intOrNull==1)
        val message=data.getValue("messageId").jsonPrimitive
        val block=data.getValue("blockIndex").jsonPrimitive
        require(message.isString&&!block.isString)
        val index=block.intOrNull?:error("화면 블록 번호가 올바르지 않습니다.")
        validate(message.content,index)
        return ChatViewportAnchor(message.content,index)
    }
    private fun validate(messageId:String,blockIndex:Int){require(identity.matches(messageId)&&blockIndex in 0..1_000_000){"화면 위치의 식별자나 블록 번호가 올바르지 않습니다."}}
}
