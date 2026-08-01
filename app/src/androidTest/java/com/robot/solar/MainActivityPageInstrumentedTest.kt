package com.robot.solar

import android.content.Context
import android.content.Intent
import android.view.View
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.Visibility
import androidx.test.espresso.matcher.ViewMatchers.isEnabled
import androidx.test.espresso.matcher.ViewMatchers.withEffectiveVisibility
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.robot.solar.ui.main.MainActivity
import org.hamcrest.Matchers.not
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityPageInstrumentedTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private var activity: MainActivity? = null

    @Before
    fun setUp() {
        context.getSharedPreferences("solar_session", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .putString("token", "instrumented-test-token")
            .putString("device_id", "crawler_00000001")
            .putString("device_name", "履带机器人A01")
            .putString("product_type", "crawler")
            .putString("email", "instrumented@test.local")
            .commit()
    }

    @After
    fun tearDown() {
        activity?.finish()
        activity = null
        context.getSharedPreferences("solar_session", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun mainPages_renderAndNavigateWithoutLoginApi() {
        launchMain()

        assertHomeControlsExist()
        openLogsAndReturn()
        assertMapControlsWorkLocally()
        assertManualControlsExist()
        assertStatusPageShowsV4Fields()
    }

    @Test
    fun offlineCommandButtons_areSafelyDisabledUntilMqttAndRobotOnline() {
        launchMain()

        onView(withId(R.id.btnStart)).check(matches(not(isEnabled())))
        onView(withId(R.id.btnStopRun)).check(matches(not(isEnabled())))
        onView(withId(R.id.btnPause)).check(matches(not(isEnabled())))
        onView(withId(R.id.btnResume)).check(matches(not(isEnabled())))
        onView(withId(R.id.btnReplan)).check(matches(not(isEnabled())))
        onView(withId(R.id.btnEmergency)).check(matches(not(isEnabled())))
        onView(withId(R.id.btnClearEstop)).check(matches(not(isEnabled())))

        onView(withId(R.id.navRemote)).perform(click())
        onView(withId(R.id.btnEnterManualMode)).check(matches(not(isEnabled())))
        onView(withId(R.id.btnReturnAutoMode)).check(matches(not(isEnabled())))
        onView(withId(R.id.btnRemoteStop)).check(matches(not(isEnabled())))
        onView(withId(R.id.btnRemoteEmergency)).check(matches(not(isEnabled())))
    }

    private fun launchMain() {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        activity = instrumentation.startActivitySync(intent) as MainActivity
        instrumentation.waitForIdleSync()
    }

    private fun assertHomeControlsExist() {
        onView(withId(R.id.sectionHome)).check(matches(withEffectiveVisibility(Visibility.VISIBLE)))
        onView(withId(R.id.btnStart)).check(matches(withEffectiveVisibility(Visibility.VISIBLE)))
        onView(withId(R.id.btnStopRun)).check(matches(withEffectiveVisibility(Visibility.VISIBLE)))
        onView(withId(R.id.btnPause)).check(matches(withEffectiveVisibility(Visibility.VISIBLE)))
        onView(withId(R.id.btnResume)).check(matches(withEffectiveVisibility(Visibility.VISIBLE)))
        onView(withId(R.id.btnReplan)).check(matches(withEffectiveVisibility(Visibility.VISIBLE)))
        onView(withId(R.id.btnEmergency)).check(matches(withEffectiveVisibility(Visibility.VISIBLE)))
        onView(withId(R.id.btnClearEstop)).check(matches(withEffectiveVisibility(Visibility.VISIBLE)))
        onView(withId(R.id.btnRetryCommand)).check(matches(withEffectiveVisibility(Visibility.VISIBLE)))
        onView(withId(R.id.btnViewLogs)).check(matches(withEffectiveVisibility(Visibility.VISIBLE)))
    }

    private fun openLogsAndReturn() {
        clickViewOnUiThread(R.id.btnViewLogs)
        onView(withId(R.id.filterGroup)).check(matches(withEffectiveVisibility(Visibility.VISIBLE)))
        onView(withId(R.id.btnClearLogs)).check(matches(withEffectiveVisibility(Visibility.VISIBLE)))
        pressBack()
        onView(withId(R.id.sectionHome)).check(matches(withEffectiveVisibility(Visibility.VISIBLE)))
    }

    private fun assertMapControlsWorkLocally() {
        onView(withId(R.id.navMap)).perform(click())
        onView(withId(R.id.sectionMap)).check(matches(withEffectiveVisibility(Visibility.VISIBLE)))
        onView(withId(R.id.btnMapReset)).perform(click())
        onView(withId(R.id.btnMapZoomIn)).perform(click())
        onView(withId(R.id.btnMapZoomOut)).perform(click())
        onView(withId(R.id.btnMapLocate)).perform(click())
        onView(withId(R.id.btnMapCenter)).perform(click())
        onView(withId(R.id.mapPageView)).check(matches(withEffectiveVisibility(Visibility.VISIBLE)))
    }

    private fun assertManualControlsExist() {
        onView(withId(R.id.navRemote)).perform(click())
        onView(withId(R.id.sectionRemote)).check(matches(withEffectiveVisibility(Visibility.VISIBLE)))
        onView(withId(R.id.btnEnterManualMode)).check(matches(withEffectiveVisibility(Visibility.VISIBLE)))
        onView(withId(R.id.btnReturnAutoMode)).check(matches(withEffectiveVisibility(Visibility.VISIBLE)))
        onView(withId(R.id.directionPad)).check(matches(withEffectiveVisibility(Visibility.VISIBLE)))
        onView(withId(R.id.manualSpeedControl)).check(matches(withEffectiveVisibility(Visibility.VISIBLE)))
        onView(withId(R.id.btnRemoteStop)).check(matches(withEffectiveVisibility(Visibility.VISIBLE)))
        onView(withId(R.id.btnRemoteEmergency)).check(matches(withEffectiveVisibility(Visibility.VISIBLE)))
    }

    private fun assertStatusPageShowsV4Fields() {
        onView(withId(R.id.navStatus)).perform(click())
        onView(withId(R.id.sectionStatus)).check(matches(withEffectiveVisibility(Visibility.VISIBLE)))
        onView(withId(R.id.tvStatusDetails)).check(matches(withEffectiveVisibility(Visibility.VISIBLE)))
    }

    private fun clickViewOnUiThread(viewId: Int) {
        instrumentation.runOnMainSync {
            activity?.findViewById<View>(viewId)?.performClick()
        }
        instrumentation.waitForIdleSync()
    }
}
