package com.user.localagent
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.user.localagent.briefing.BriefingReviewActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
@RunWith(AndroidJUnit4::class)
class BriefingReviewUiValidation {
    @get:Rule val compose=createAndroidComposeRule<BriefingReviewActivity>()
    @Test fun phoneReportDestinationAndReviewEntryAreVisible(){compose.onNodeWithText("일정·업무 확인").assertIsDisplayed();compose.onNodeWithText("자동 등록할 캘린더").assertIsDisplayed()}
}
