package ru.chesscoach.overlay

import android.content.Intent
import android.app.UiAutomation
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiScrollable
import androidx.test.uiautomator.UiSelector
import androidx.test.uiautomator.Until
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import ru.chesscoach.overlay.capture.CaptureService
import ru.chesscoach.overlay.safety.ChessAccessibilityService
import java.io.File
import java.util.regex.Pattern

/** Exercises the actual Android 35 consent -> foreground service -> overlay -> stop path. */
@RunWith(AndroidJUnit4::class)
class CaptureLifecycleTest {
    @Test fun consentCaptureAndStopWithoutLeakingAdviceOutsideChess() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        Configurator.getInstance().setUiAutomationFlags(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val device = UiDevice.getInstance(instrumentation)
        val component = "${context.packageName}/ru.chesscoach.overlay.safety.ChessAccessibilityService"
        val oldServices = device.executeShellCommand("settings get secure enabled_accessibility_services").trim()
        device.executeShellCommand("appops set ${context.packageName} SYSTEM_ALERT_WINDOW allow")
        device.executeShellCommand("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        device.executeShellCommand("settings put secure enabled_accessibility_services $component")
        device.executeShellCommand("settings put secure accessibility_enabled 1")
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            val connected = waitFor(10000) { ChessAccessibilityService.instance != null }
            assertTrue("Accessibility guard did not connect", connected)
            UiScrollable(UiSelector().scrollable(true)).scrollTextIntoView("Начать тренировку")
            checkNotNull(device.wait(Until.findObject(By.text("Начать тренировку")), 5000)).click()
            // Android's native dialog theme transforms button text to uppercase.
            checkNotNull(device.wait(Until.findObject(By.res("android", "button1")), 5000)).click()
            val consent = device.wait(Until.findObject(By.res("com.android.systemui", "start_button")), 4000)
                ?: device.wait(Until.findObject(By.res("android", "button1")), 4000)
                ?: device.wait(Until.findObject(By.text(Pattern.compile("Start now|Start|Начать|Начать сейчас"))), 4000)
            assertNotNull("Android screen-capture consent button missing", consent)
            consent!!.click()
            assertTrue("Projection service failed to start", waitFor(12000) { AppState.running })
            assertTrue("No actual projection frame received", waitFor(10000) { AppState.capturedFrames > 0 })
            assertFalse(ChessAccessibilityService.permits())
            assertNull(AppState.advice.arrow); assertNull(AppState.advice.evaluation)
            context.stopService(Intent(context, CaptureService::class.java))
            assertTrue("Projection did not stop", waitFor(5000) { !AppState.running })
            assertNull(AppState.boardRect); assertNull(AppState.advice.arrow)
        } finally {
            device.takeScreenshot(File(context.getExternalFilesDir(null), "capture-lifecycle.png"))
            context.stopService(Intent(context, CaptureService::class.java))
            instrumentation.runOnMainSync { activity.finish() }
            if (oldServices.isBlank() || oldServices == "null") {
                device.executeShellCommand("settings delete secure enabled_accessibility_services")
                device.executeShellCommand("settings put secure accessibility_enabled 0")
            } else device.executeShellCommand("settings put secure enabled_accessibility_services $oldServices")
        }
    }
    private fun waitFor(timeout: Long, predicate: () -> Boolean): Boolean {
        val deadline = android.os.SystemClock.elapsedRealtime() + timeout
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            if (predicate()) return true
            android.os.SystemClock.sleep(100)
        }
        return predicate()
    }
}
