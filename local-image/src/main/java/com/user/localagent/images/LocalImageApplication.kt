package com.user.localagent.images

import com.user.localagent.LocalAgentApplication

/** Separate package/UID and data; no assistant, call follow-up, remote QA or update startup. */
class LocalImageApplication : LocalAgentApplication() {
    override val startAssistantBackgroundWork: Boolean = false
}
