package com.user.localagent.ui

/** A stable owner-visible location. Null message means following the latest answer. */
data class UiChatViewport(
    val threadId:String,val branchId:String,val messageId:String?=null,val offset:Int=0,val blockIndex:Int=0,
)

internal data class ViewportMessage(val id:String,val blocks:Int)
internal data class ViewportTarget(val itemIndex:Int,val offset:Int,val followsBottom:Boolean)

internal fun viewportMatches(view:UiChatViewport?,threadId:String,branchId:String):Boolean =
    view!=null&&view.threadId==threadId&&view.branchId==branchId

/** Block ordinal is relative to the full message, including all numbered generation parts. */
internal fun restoreViewport(messages:List<ViewportMessage>,view:UiChatViewport,busy:Boolean,leadingItems:Int=0):ViewportTarget {
    var start=leadingItems
    for(message in messages){
        if(message.id==view.messageId)return ViewportTarget(start+view.blockIndex.coerceIn(0,(message.blocks-1).coerceAtLeast(0)),view.offset.coerceAtLeast(0),false)
        start+=message.blocks
    }
    // A deleted/non-member anchor is never redirected into another room's message.
    return ViewportTarget(start+if(busy)1 else 0,0,true)
}

internal fun captureViewport(threadId:String,branchId:String,messages:List<ViewportMessage>,itemIndex:Int,offset:Int,followBottom:Boolean,leadingItems:Int=0):UiChatViewport {
    if(followBottom)return UiChatViewport(threadId,branchId)
    if(itemIndex<leadingItems&&messages.isNotEmpty())return UiChatViewport(threadId,branchId,messages.first().id)
    var start=leadingItems
    for(message in messages){
        if(itemIndex in start until start+message.blocks)return UiChatViewport(threadId,branchId,message.id,offset.coerceAtLeast(0),itemIndex-start)
        start+=message.blocks
    }
    return UiChatViewport(threadId,branchId)
}
