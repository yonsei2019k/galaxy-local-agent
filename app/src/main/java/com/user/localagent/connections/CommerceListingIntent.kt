package com.user.localagent.connections

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.verify.domain.DomainVerificationManager
import android.content.pm.verify.domain.DomainVerificationUserState
import android.net.Uri
import com.user.localagent.core.connections.CommerceListingLinks

object CommerceListingIntent {
    fun prepare(context: Context, uri: Uri): Intent {
        val fallback = Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)
        val merchant = CommerceListingLinks.packageFor(uri.toString()) ?: return fallback
        val state = try {
            context.getSystemService(DomainVerificationManager::class.java)?.getDomainVerificationUserState(merchant)
        } catch (_: PackageManager.NameNotFoundException) {
            null
        } catch (_: SecurityException) {
            null
        }
        if (state?.isLinkHandlingAllowed != true ||
            state.hostToStateMap[uri.host] != DomainVerificationUserState.DOMAIN_STATE_VERIFIED) return fallback
        val target = Intent(fallback).setPackage(merchant)
        return if (target.resolveActivity(context.packageManager) != null) target else fallback
    }
}
