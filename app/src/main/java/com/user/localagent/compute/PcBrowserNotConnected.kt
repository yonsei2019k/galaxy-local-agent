package com.user.localagent.compute

/** Only a negative connection probe before dispatch can issue this no-effect receipt. */
internal class PcBrowserNotConnected : PcUnavailable(com.user.localagent.core.WebsiteExecutionPolicy.RECONNECT_NOTICE)
