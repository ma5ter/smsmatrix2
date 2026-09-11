package su.mya.sms_matrix

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Monitors device battery level changes and reports critical thresholds (<20%, <10%, <5%)
 * to the Matrix user as service notices.
 */
class BatteryMonitor(
	private val context: Context
) {
	private var batteryReceiver: BroadcastReceiver? = null
	private val alertedThresholds = mutableSetOf<Int>()

	companion object {
		private const val TAG = "BatteryMonitor"
		const val SYSTEM_PHONE_NUMBER = "System"
	}

	/**
	 * Registers the receiver for sticky battery change broadcasts.
	 */
	fun startBatteryMonitoring() {
		if (batteryReceiver != null) return

		val receiver = object : BroadcastReceiver() {
			override fun onReceive(ctx: Context, intent: Intent) {
				if (intent.action == Intent.ACTION_BATTERY_CHANGED) {
					processBatteryIntent(intent)
				}
			}
		}
		batteryReceiver = receiver

		val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
		ContextCompat.registerReceiver(
			context,
			receiver,
			filter,
			ContextCompat.RECEIVER_NOT_EXPORTED
		)
	}

	/**
	 * Unregisters the receiver and stops monitoring.
	 */
	fun stopBatteryMonitoring() {
		try {
			batteryReceiver?.let { context.unregisterReceiver(it) }
		} catch (e: Exception) {
			Log.e(TAG, "Failed to unregister battery receiver", e)
		} finally {
			batteryReceiver = null
		}
	}

	private fun processBatteryIntent(intent: Intent) {
		val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
		val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
		if (level < 0 || scale <= 0) return

		val batteryPct = (level * 100) / scale

		// Reset triggered thresholds as the battery charges back up
		if (batteryPct >= 20) {
			alertedThresholds.clear()
		} else if (batteryPct >= 10) {
			alertedThresholds.removeAll(listOf(10, 5))
		} else if (batteryPct >= 5) {
			alertedThresholds.remove(5)
		}

		val thresholdToAlert = when {
			batteryPct < 5 -> {
				alertedThresholds.add(20)
				alertedThresholds.add(10)
				5
			}
			batteryPct < 10 -> {
				alertedThresholds.add(20)
				10
			}
			batteryPct < 20 -> 20
			else -> null
		}

		if (thresholdToAlert != null && alertedThresholds.add(thresholdToAlert)) {
			val message = "Battery alert: level is $batteryPct% (below $thresholdToAlert%)"
			AppLogger.log(message, LogLevel.WARNING)
			Utilities.sendMatrix(
				context,
				message,
				SYSTEM_PHONE_NUMBER,
				MatrixHelper.MESSAGE_TYPE_NOTICE
			)
		}
	}
}
