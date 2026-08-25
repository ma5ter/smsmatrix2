package su.mya.sms_matrix

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.Telephony
import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class UnreadSmsProcessor(private val context: Context) {

	companion object {
		private const val TAG = "UnreadSmsProcessor"
		private const val PROCESSED_SMS_PREFS = "processed_sms_ids"
		private val SMS_INBOX_URI: Uri = Telephony.Sms.Inbox.CONTENT_URI
		private val processMutex = Mutex()
	}

	private val processedPrefs = context.getSharedPreferences(PROCESSED_SMS_PREFS, Context.MODE_PRIVATE)

	private fun isSmsTracked(smsId: Long): Boolean {
		return processedPrefs.getBoolean("sms_$smsId", false)
	}

	private fun trackSmsId(smsId: Long) {
		processedPrefs.edit().putBoolean("sms_$smsId", true).commit()
	}

	/**
	 * Scans unread SMS from the telephony inbox, sends them to Matrix sequentially, and marks them read.
	 */
	suspend fun processUnreadSms(matrixHelper: MatrixHelper) = processMutex.withLock {
		val projection = arrayOf(
			Telephony.Sms._ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE
		)
		val selection = "${Telephony.Sms.READ} = 0"
		val sortOrder = "${Telephony.Sms.DATE} ASC"

		val unreadMessages = mutableListOf<SmsItem>()

		try {
			context.contentResolver.query(SMS_INBOX_URI, projection, selection, null, sortOrder)?.use { cursor ->
				val idCol = cursor.getColumnIndex(Telephony.Sms._ID)
				val addressCol = cursor.getColumnIndex(Telephony.Sms.ADDRESS)
				val bodyCol = cursor.getColumnIndex(Telephony.Sms.BODY)

				while (cursor.moveToNext()) {
					val id = if (idCol >= 0) cursor.getLong(idCol) else -1L
					val address = if (addressCol >= 0) cursor.getString(addressCol).orEmpty() else ""
					val body = if (bodyCol >= 0) cursor.getString(bodyCol).orEmpty() else ""

					if (id != -1L && address.isNotBlank() && body.isNotBlank()) {
						if (!isSmsTracked(id)) {
							unreadMessages.add(SmsItem(id, address, body))
						}
					}
				}
			}
		} catch (e: Exception) {
			AppLogger.log("Failed to query unread SMS: ${e.message}", LogLevel.ERROR)
			Log.e(TAG, "Error querying unread SMS", e)
			return@withLock
		}

		if (unreadMessages.isEmpty()) {
			AppLogger.log("No unread SMS messages found at startup", LogLevel.INFO)
			return@withLock
		}

		AppLogger.log("Found ${unreadMessages.size} unread SMS. Forwarding to Matrix...", LogLevel.INFO)

		for (item in unreadMessages) {
			if (isSmsTracked(item.id)) {
				continue
			}
			val contact = matrixHelper.getContactName(item.address)
			val success = matrixHelper.sendTextMessageDirect(
				phoneNumber = item.address, body = item.body, type = MatrixHelper.MESSAGE_TYPE_TEXT
			)

			if (success) {
				trackSmsId(item.id)
				markSmsAsReadInTelephony(item.id)
				AppLogger.log("Forwarded unread SMS from $contact and marked as read", LogLevel.SUCCESS)
			} else {
				AppLogger.log("Failed to forward unread SMS from $contact", LogLevel.ERROR)
			}
		}
	}

	/**
	 * Updates the SMS database record state to read and seen.
	 */
	private fun markSmsAsReadInTelephony(smsId: Long) {
		try {
			val values = ContentValues().apply {
				put(Telephony.Sms.READ, 1)
				put(Telephony.Sms.SEEN, 1)
			}
			val rowsUpdated = context.contentResolver.update(
				Telephony.Sms.CONTENT_URI, values, "${Telephony.Sms._ID} = ?", arrayOf(smsId.toString())
			)
			if (rowsUpdated <= 0) {
				context.contentResolver.update(
					SMS_INBOX_URI, values, "${Telephony.Sms._ID} = ?", arrayOf(smsId.toString())
				)
			}
		} catch (e: Exception) {
			Log.e(TAG, "Failed to update telephony read state for SMS $smsId", e)
		}
	}

	private data class SmsItem(val id: Long, val address: String, val body: String)
}