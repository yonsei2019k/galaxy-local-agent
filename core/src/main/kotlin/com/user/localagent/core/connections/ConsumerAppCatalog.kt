package com.user.localagent.core.connections

import com.user.localagent.core.*
import java.net.URLEncoder
import kotlinx.serialization.json.jsonPrimitive

enum class ConsumerApp(val id:String,val label:String,val packageName:String) {
    KORAIL("korail","코레일+","com.korail.talk"),
    SRT("srt","SRT","kr.co.srail.newapp"),
    TMAP("tmap","티맵","com.skt.tmap.ku"),
    NETFLIX("netflix","넷플릭스","com.netflix.mediaclient"),
    COUPANG("coupang","쿠팡","com.coupang.mobile"),
    EATS("coupangeats","쿠팡이츠","com.coupang.mobile.eats")
}
data class ConsumerTarget(val app:ConsumerApp,val uri:String?=null)

/** Fixed consumer-app intents only. This is not an account/private-order API. */
object ConsumerAppCatalog {
    private fun text(required:Boolean=true,max:Int=500)=Argument("string",required,max)
    private fun tool(name:String,description:String,vararg arguments:Pair<String,Argument>)=
        ToolDefinition(name,Risk.LOW,mapOf(*arguments),description)
    val definitions=listOf(
        tool("open_korail_booking","Open the official Korean rail booking app or website on the phone for schedules and seat selection. Opening does not verify seats, fares, reservations or tickets."),
        tool("open_korail_guest_receipt","Open the official Korail nonmember receipt form for the owner. No passenger information is supplied or returned."),
        tool("open_srt_guest_receipt","Open the official SR nonmember receipt form for an SRT ticket. The owner enters the ticket number on the phone. Does not verify receipt or ticket issuance."),
        tool("open_tmap","Open the installed TMAP app, or show an explicitly requested place/address using the Android geo intent. Does not start driving or book/pay for transport.","query" to text(false)),
        tool("open_tmap_destination","Show an explicit WGS84 destination in TMAP. Coordinates are strings. Opens the destination screen; does not claim navigation started or arrival time verified.","name" to text(max=200),"latitude" to text(max=30),"longitude" to text(max=30)),
        tool("open_netflix","Open the installed Netflix app using its existing login. Does not read private viewing history or start a paid subscription."),
        tool("search_netflix","Open the official Netflix search page for the stated title/query. Uses a browser when the installed app does not handle the link. Does not verify availability or playback.","query" to text()),
        tool("open_netflix_title","Open one official Netflix title page using its numeric title ID. Does not bypass profile login or verify playback.","id" to text(max=20)),
        tool("open_coupang","Open the installed Coupang shopping app with its existing login. No cart, purchase, payment or private-order API."),
        tool("search_coupang","Open the official Coupang product search page. Falls back to the browser if the installed app does not handle the link. Displayed prices and stock still need actual page verification.","query" to text()),
        tool("open_coupang_product","Open a Coupang product page. Supply both itemId and vendorItemId when opening a compared seller option. Opening does not purchase anything.","id" to text(max=30),"itemId" to text(false,24),"vendorItemId" to text(false,24)),
        tool(CoupangReviewEvidence.OPEN_TOOL,"Open the private on-phone Coupang review workspace for the owner to inspect orders, compare reviewable purchase options and prepare a review. Opens an internal screen only. Authentication and publishing are performed by the owner."),
        tool("open_coupang_eats","Open the installed Coupang Eats app using its current login. Does not submit an order, pay, or expose the customer's private order history.")
    )
    val webFallbackTools = setOf("search_netflix", "open_netflix_title", "search_coupang", "open_coupang_product",
        "open_korail_booking", "open_korail_guest_receipt", "open_srt_guest_receipt")
    val names=definitions.map {it.name}.toSet()
    val appForTool=mapOf("open_korail_booking" to ConsumerApp.KORAIL, "open_korail_guest_receipt" to ConsumerApp.KORAIL,
        "open_srt_guest_receipt" to ConsumerApp.SRT, "open_tmap" to ConsumerApp.TMAP,"open_tmap_destination" to ConsumerApp.TMAP,
        "open_netflix" to ConsumerApp.NETFLIX,"search_netflix" to ConsumerApp.NETFLIX,"open_netflix_title" to ConsumerApp.NETFLIX,
        "open_coupang" to ConsumerApp.COUPANG,"search_coupang" to ConsumerApp.COUPANG,"open_coupang_product" to ConsumerApp.COUPANG,
        "open_coupang_eats" to ConsumerApp.EATS, CoupangReviewEvidence.OPEN_TOOL to ConsumerApp.COUPANG)
    private fun encode(value:String):String {
        require(value.isNotBlank()&&value.length<=500&&value.none {it.isISOControl()})
        return URLEncoder.encode(value,"UTF-8").replace("+","%20")
    }
    fun target(call:ToolCall):ConsumerTarget {
        require(call.name in names);ToolRegistry.validate(call)
        val app=appForTool.getValue(call.name)
        fun value(key:String)=call.arguments.getValue(key).jsonPrimitive.content
        fun id(maximum:Int):String=value("id").also {require(it.matches(Regex("[0-9]{1,$maximum}")))}
        val uri=when(call.name){
            "open_korail_booking"->"https://www.korail.com/ticket/main"
            "open_korail_guest_receipt"->"https://www.korail.com/ticket/reserve/nonmember/receipt"
            "open_srt_guest_receipt"->"https://eticket.srail.kr/hpg/hma/04/selectReceiptForm.do?pageId=TK0104050000"
            "open_tmap"->call.arguments["query"]?.jsonPrimitive?.content?.takeIf {it.isNotBlank()}?.let {"geo:0,0?q="+encode(it)}
            "open_tmap_destination"->{
                val lat=value("latitude").toDouble();val lon=value("longitude").toDouble()
                require(lat.isFinite()&&lon.isFinite()&&lat in -90.0..90.0&&lon in -180.0..180.0)
                "geo:0,0?q=${lat},${lon}("+encode(value("name"))+")"
            }
            "search_netflix"->"https://www.netflix.com/search?q="+encode(value("query"))
            "open_netflix_title"->"https://www.netflix.com/title/"+id(20)
            "search_coupang"->"https://www.coupang.com/np/search?q="+encode(value("query"))
            "open_coupang_product"->{
                val product = id(30)
                val identifiers = listOf("itemId", "vendorItemId")
                if (identifiers.none { it in call.arguments }) "https://www.coupang.com/vp/products/$product"
                else {
                    require(identifiers.all { it in call.arguments }) { "Both Coupang option identifiers are required." }
                    val option = CoupangReviewEvidence.Variant(product, value("itemId"), value("vendorItemId"))
                    require(CoupangReviewEvidence.variant(option.url) == option) { "Invalid Coupang seller option." }
                    option.url
                }
            }
            else->null
        }
        return ConsumerTarget(app,uri)
    }
}
