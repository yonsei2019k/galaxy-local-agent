package com.user.localagent

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.LinearLayout

/** Visible synthetic controls in the test package, with no owner data or network access. */
class ScreenObservationFixtureActivity:Activity() {
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        val list=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            accessibilityDelegate=object:View.AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(host:View,info:AccessibilityNodeInfo) {
                    super.onInitializeAccessibilityNodeInfo(host,info)
                    info.collectionInfo=AccessibilityNodeInfo.CollectionInfo(3,1,false,
                        AccessibilityNodeInfo.CollectionInfo.SELECTION_MODE_NONE)
                }
            }
        }
        repeat(3){row->
            list.addView(Button(this).apply {
                text="Choose"
                isEnabled=row<2
                accessibilityDelegate=object:View.AccessibilityDelegate() {
                    override fun onInitializeAccessibilityNodeInfo(host:View,info:AccessibilityNodeInfo) {
                        super.onInitializeAccessibilityNodeInfo(host,info)
                        info.collectionItemInfo=AccessibilityNodeInfo.CollectionItemInfo(row,1,0,1,false)
                    }
                }
                setOnClickListener {
                    isSelected=true
                    text="Chosen row ${row+1}"
                }
            })
        }
        setContentView(list)
    }
}

