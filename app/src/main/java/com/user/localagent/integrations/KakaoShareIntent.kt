package com.user.localagent.integrations

import android.content.Intent
import com.user.localagent.core.KakaoShare

/** No recipient, activity class, URI, stream, callback or broadcast can be supplied by the model. */
object KakaoShareIntent {
    fun prepare(text: String? = null): Intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        setPackage(KakaoShare.PACKAGE)
        if (text != null) putExtra(Intent.EXTRA_TEXT, text)
    }
}
