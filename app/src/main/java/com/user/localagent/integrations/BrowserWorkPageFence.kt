package com.user.localagent.integrations

import com.user.localagent.integrations.AppWebAgent.WorkBinding

internal fun ownsBrowserPage(owner:WorkBinding?,caller:WorkBinding?):Boolean=owner==caller

internal fun canInheritBrowserPage(owner:WorkBinding?,parent:WorkBinding,child:WorkBinding):Boolean =
    owner==parent&&parent.runId!=child.runId&&parent.sameConversation(child)
