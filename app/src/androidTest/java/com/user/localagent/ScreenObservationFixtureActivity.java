package com.user.localagent;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.LinearLayout;

/** Synthetic controls in the standalone test process, without production runtime dependencies. */
public final class ScreenObservationFixtureActivity extends Activity {
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override
            public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setCollectionInfo(new AccessibilityNodeInfo.CollectionInfo(
                    3, 1, false, AccessibilityNodeInfo.CollectionInfo.SELECTION_MODE_NONE));
            }
        });
        for (int index = 0; index < 3; index++) {
            final int row = index;
            Button button = new Button(this);
            button.setText("Choose");
            button.setEnabled(row < 2);
            button.setAccessibilityDelegate(new View.AccessibilityDelegate() {
                @Override
                public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                    super.onInitializeAccessibilityNodeInfo(host, info);
                    info.setCollectionItemInfo(
                        new AccessibilityNodeInfo.CollectionItemInfo(row, 1, 0, 1, false));
                }
            });
            button.setOnClickListener(view -> {
                button.setSelected(true);
                button.setText("Chosen row " + (row + 1));
                android.util.Log.i("ZipHapScreenFixture", "chosenRow=" + row);
            });
            rows.addView(button);
        }
        setContentView(rows);
    }
}

