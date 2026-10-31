package com.user.localagent.compute

/** Private credentials are captured once; status/discovery changes never choose a new compute owner. */
internal class ImageComputePairPin(private val serverId:String?,private val certificateSha256:String?,
    private val token:String?,private val imagesAllowedAtCapture:Boolean,private val authorizationRevision:Long) {
    fun permits(currentServerId:String?,currentCertificate:String?,currentToken:String?,imagesAllowed:Boolean,currentRevision:Long):Boolean =
        authorizationRevision>=0&&currentRevision==authorizationRevision&&
            imagesAllowedAtCapture&&imagesAllowed&&!serverId.isNullOrBlank()&&!certificateSha256.isNullOrBlank()&&!token.isNullOrBlank()&&
            serverId==currentServerId&&certificateSha256==currentCertificate&&token==currentToken
    override fun toString()="ImageComputePairPin(privateCredentialsOmitted=true)"
}
