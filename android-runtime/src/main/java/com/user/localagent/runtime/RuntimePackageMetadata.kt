package com.user.localagent.runtime

import android.content.Context
import android.content.pm.PackageManager

/** Installed-app identity; a shared AAR cannot use a consumer's generated BuildConfig. */
class RuntimePackageMetadata(context: Context) {
    private val app = context.applicationContext
    private val installed by lazy { app.packageManager.getPackageInfo(app.packageName, 0) }
    val versionName: String get() = installed.versionName.orEmpty()
    val versionCode: Long get() = installed.longVersionCode
    val updateUrl: String by lazy {
        app.packageManager.getApplicationInfo(app.packageName, PackageManager.GET_META_DATA)
            .metaData?.getString("com.user.localagent.UPDATE_URL").orEmpty()
    }
}
