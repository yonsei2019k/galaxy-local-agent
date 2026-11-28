package com.user.localagent.ui

/**
 * 나의 집순이 > 집순이 모습 바꾸기. The one character picker lives in the chat WebView (`CharacterPicker`), so the
 * native row moves to the chat and sets a bounded epoch (`openCharacterPicker` in the snapshot). The web opens the
 * picker once and answers `characterPickerShown` with that epoch, which clears the flag. The choice itself still goes
 * through the existing `setCharacter` path (`ReactBridgePolicy`, `CharacterPreference.accepts`).
 */
internal object CharacterPickerFlag {
    const val MAX_EPOCH=9007199254740991L

    /** Next epoch after [last]; wraps before the web's safe-integer limit. */
    fun next(last:Long):Long = if(last<0||last>=MAX_EPOCH)1 else last+1

    /** The pending flag after the web acknowledged [acknowledged]; another epoch leaves it pending. */
    fun cleared(pending:Long?,acknowledged:Long):Long? = if(pending!=null&&pending==acknowledged)null else pending
}

/** Plain-word shortcut rows on the 나의 집순이 screen, mapped to their existing actions. */
internal enum class AssistantShortcut(val label:String) {
    CHANGE_CHARACTER("집순이 모습 바꾸기"),
}

internal class AssistantShortcutActions(private val changeCharacter:()->Unit) {
    fun run(shortcut:AssistantShortcut) = when(shortcut) {
        AssistantShortcut.CHANGE_CHARACTER->changeCharacter()
    }
}
