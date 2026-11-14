package com.user.localagent.core

/** Host-only rejection before a mutation was applied; ordinary failures never imply this guarantee. */
class ToolPreconditionRejected(message:String):IllegalStateException(message)
