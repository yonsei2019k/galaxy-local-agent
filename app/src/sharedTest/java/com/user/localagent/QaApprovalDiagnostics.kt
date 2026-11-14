package com.user.localagent

import java.security.MessageDigest
import kotlinx.serialization.json.*

/** Test-only private diagnostics. Callback persists into the existing private AtomicFile report. */
class QaApprovalDiagnostics(private val persistPrivate:(JsonObject)->Unit={}) {
    private data class Attempt(val tool:String,val args:JsonObject?,val argsChars:Int,val argsSha256:String,
        val checks:MutableList<JsonObject> = mutableListOf(),var phase:String="CAPTURED")
    private val attempts=mutableListOf<Attempt>()
    var lastCheckCode:String?=null;private set
    var failureCode:String?=null;private set
    private var omittedAttempts=0

    fun capture(tool:String,resolvedArguments:JsonObject):Int {
        val raw=resolvedArguments.toString()
        if(attempts.size>=32){omittedAttempts++;publish();return -1}
        val snapshot=if(raw.length<=32_768)Json.parseToJsonElement(raw).jsonObject else null
        attempts+=Attempt(tool,snapshot,raw.length,digest(raw))
        publish() // Before binding/tool/content assertions and before any QA approval is submitted.
        return attempts.lastIndex
    }
    fun assertCondition(index:Int,code:String,condition:Boolean){record(index,code,condition,null)}
    fun assertExactContent(index:Int,code:String,expected:String,actual:JsonElement?) {
        val primitive=actual as? JsonPrimitive
        val text=primitive?.takeIf{it.isString}?.content
        val same=text!=null&&text==expected
        val limit=minOf(expected.length,text?.length?:0)
        val first=if(same)null else (0 until limit).firstOrNull{expected[it]!=text!![it]}?:limit
        val comparison=buildJsonObject {
            put("comparison","exact_string_no_normalization");put("equal",same)
            put("actualPresent",actual!=null);put("actualIsString",text!=null)
            // Full proposed text is already in resolvedArguments. Expected value stays in PRIVATE output too.
            put("expected",if(expected.length<=12_000)JsonPrimitive(expected)else JsonNull)
            put("expectedValueOmitted",expected.length>12_000)
            put("expectedUtf16Length",expected.length);put("actualUtf16Length",text?.length?.let(::JsonPrimitive)?:JsonNull)
            put("expectedUtf8Bytes",expected.toByteArray(Charsets.UTF_8).size)
            put("actualUtf8Bytes",text?.toByteArray(Charsets.UTF_8)?.size?.let(::JsonPrimitive)?:JsonNull)
            put("expectedSha256",digest(expected));put("actualSha256",text?.let{JsonPrimitive(digest(it))}?:JsonNull)
            put("firstDifferenceUtf16",first?.let(::JsonPrimitive)?:JsonNull)
            put("expectedLfCount",expected.count{it=='\n'});put("actualLfCount",text?.count{it=='\n'}?.let(::JsonPrimitive)?:JsonNull)
            put("expectedCrCount",expected.count{it=='\r'});put("actualCrCount",text?.count{it=='\r'}?.let(::JsonPrimitive)?:JsonNull)
        }
        record(index,code,same,comparison)
    }
    fun markCheck(code:String){lastCheckCode=code;publish()}
    fun approvalSubmitted(index:Int,accepted:Boolean) {
        attempts.getOrNull(index)?.phase=if(accepted)"APPROVAL_SUBMITTED"else"APPROVAL_ALREADY_TERMINAL"
        publish()
    }
    fun snapshot():JsonObject=buildJsonObject {
        put("formatVersion",1);put("privateRuntimeData",true);put("lastCheckCode",lastCheckCode)
        put("qaFailureCode",failureCode);put("omittedAttempts",omittedAttempts)
        put("approvalAttempts",buildJsonArray{attempts.forEachIndexed{index,attempt->add(buildJsonObject{
            put("attemptIndex",index);put("tool",attempt.tool);put("phase",attempt.phase)
            put("resolvedArguments",attempt.args?:JsonNull);put("argumentsOmitted",attempt.args==null)
            put("argumentsJsonChars",attempt.argsChars);put("argumentsSha256",attempt.argsSha256)
            put("checks",JsonArray(attempt.checks.toList()))
        })}})
    }
    private fun record(index:Int,code:String,condition:Boolean,comparison:JsonObject?) {
        lastCheckCode=code
        attempts.getOrNull(index)?.let{attempt->
            if(attempt.checks.size<24)attempt.checks+=buildJsonObject{
                put("code",code);put("passed",condition);comparison?.let{put("comparison",it)}
            }
            attempt.phase=if(condition)"GUARD_PASSED"else"GUARD_REJECTED"
        }
        if(!condition)failureCode=code
        publish() // Equality diagnostics durable BEFORE the failed guard throws.
        check(condition){code} // Fixed code only; never put raw content/IDs in an exception message.
    }
    private fun publish(){persistPrivate(snapshot())}
    private fun digest(text:String)=MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it.toInt()and 255)}
    override fun toString()="QaApprovalDiagnostics(private test runtime output)"
}
