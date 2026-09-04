package ali.paf.contacts.util

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.View

/**
 * Performs a robust haptic feedback pulse.
 * It first attempts to use the standard [performHapticFeedback] with flags to ignore
 * global settings. If that fails (e.g., on some devices or if disabled), it falls back
 * to using the [Vibrator] service directly for a short pulse.
 */
fun View.performRobustHapticFeedback() {
    // Try the standard view-based haptic feedback first
    // FLAG_IGNORE_GLOBAL_SETTING ensures it works even if "Touch feedback" is off in system settings
    val success = performHapticFeedback(
        HapticFeedbackConstants.VIRTUAL_KEY,
        HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING or HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING
    )

    // If it failed to trigger or returned false, fallback to the Vibrator service directly
    if (!success) {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vibratorManager?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }

            vibrator?.let {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    it.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    it.vibrate(50)
                }
            }
        } catch (e: Exception) {
            // Ignore vibration failures to prevent crashes
        }
    }
}
