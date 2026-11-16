package com.user.localagent.compute

import java.io.IOException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal fun PcConnection.samePair(other:PcConnection)=serverId==other.serverId&&
    certificateSha256==other.certificateSha256&&token==other.token

/** Route changes do not grant access to another PC or replay a computation. */
internal fun originalPcRoutes(original:PcConnection,saved:PcConnection?,failedEndpoint:String):List<PcConnection> {
    val same=saved?.takeIf{original.samePair(it)}
    return listOfNotNull(same?.endpoint,original.endpoint,same?.overlayEndpoint,original.overlayEndpoint)
        .distinct().filter{it!=failedEndpoint}.map{original.copy(endpoint=it)}
}

internal suspend fun findOriginalPcRoute(original:PcConnection,saved:PcConnection?,failedEndpoint:String,
    probe:suspend(PcConnection)->Boolean):PcConnection? {
    for(candidate in originalPcRoutes(original,saved,failedEndpoint)) {
        currentCoroutineContext().ensureActive()
        val reached=try{probe(candidate)}catch(_:IOException){false}
        currentCoroutineContext().ensureActive()
        if(reached)return candidate
    }
    return null
}
