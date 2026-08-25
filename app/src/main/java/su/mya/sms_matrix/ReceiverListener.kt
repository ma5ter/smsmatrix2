package su.mya.sms_matrix

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat

class ReceiverListener : BroadcastReceiver() {

	override fun onReceive(context: Context, intent: Intent) {
		when (intent.action) {
			Telephony.Sms.Intents.SMS_RECEIVED_ACTION -> {
				handleIncomingSMS(context, intent)
			}

			TelephonyManager.ACTION_PHONE_STATE_CHANGED -> {
				handleIncomingCall(context, intent)
			}

			Intent.ACTION_BOOT_COMPLETED -> {
				AppLogger.log("Device boot completed. Launching MatrixService...", LogLevel.INFO)
				val serviceIntent = Intent(context, MatrixService::class.java)
				ContextCompat.startForegroundService(context, serviceIntent)
			}
		}
	}

	private fun handleIncomingSMS(context: Context, intent: Intent) {
		val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
		if (messages.isEmpty()) return

		val messageMap = mutableMapOf<String, StringBuilder>()

		for (sms in messages) {
			val address = sms.originatingAddress ?: continue
			val body = sms.messageBody ?: continue

			messageMap.getOrPut(address) { StringBuilder() }.append(body)
		}

		for ((address, text) in messageMap) {
			AppLogger.log("Received incoming SMS from $address", LogLevel.EVENT)
			Utilities.sendMatrix(context, text.toString(), address, MatrixHelper.MESSAGE_TYPE_TEXT)
		}
	}

	private fun handleIncomingCall(context: Context, intent: Intent) {
		val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
		val caller = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER) ?: return

		val body = when (state) {
			TelephonyManager.EXTRA_STATE_IDLE -> "$caller ended call"
			TelephonyManager.EXTRA_STATE_OFFHOOK -> "$caller answered call"
			TelephonyManager.EXTRA_STATE_RINGING -> "$caller is calling"
			else -> return
		}
		AppLogger.log("Call event: $body", LogLevel.EVENT)
		Utilities.sendMatrix(context, body, caller, MatrixHelper.MESSAGE_TYPE_NOTICE)
	}
}