package su.mya.sms_matrix

import android.content.ContentResolver
import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Telephony
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream

/**
 * Monitors the system MMS database for newly received multimedia messages.
 */
class MMSMonitor(
	private val context: Context
) {
	private val contentResolver: ContentResolver = context.contentResolver
	private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
	private var mmsObserver: ContentObserver? = null
	private var lastMmsCount = 0

	companion object {
		private const val TAG = "MMSMonitor"
		private val MMS_URI: Uri = Uri.parse("content://mms")
		private val MMS_INBOX_URI: Uri = Uri.parse("content://mms/inbox")
		private val MMS_PART_URI: Uri = Uri.parse("content://mms/part")
	}

	/**
	 * Registers the content observer and captures initial message count snapshot.
	 */
	fun startMMSMonitoring() {
		try {
			lastMmsCount = queryCurrentMmsCount()
			Log.d(TAG, "Init MMSCount = $lastMmsCount")

			val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
				override fun onChange(selfChange: Boolean) {
					super.onChange(selfChange)
					scope.launch {
						processMmsChange()
					}
				}
			}
			mmsObserver = observer
			contentResolver.registerContentObserver(MMS_URI, true, observer)
		} catch (e: Exception) {
			Log.e(TAG, "Failed to start MMS monitoring", e)
		}
	}

	/**
	 * Unregisters the content observer and releases background coroutine scope.
	 */
	fun stopMMSMonitoring() {
		try {
			mmsObserver?.let { contentResolver.unregisterContentObserver(it) }
			mmsObserver = null
			scope.cancel()
		} catch (e: Exception) {
			Log.e(TAG, "Failed to stop MMS monitoring", e)
		}
	}

	private fun queryCurrentMmsCount(): Int {
		val projection = arrayOf(Telephony.Mms._ID)
		val selection = "${Telephony.Mms.MESSAGE_BOX} = ${Telephony.Mms.MESSAGE_BOX_INBOX}"
		return try {
			contentResolver.query(MMS_INBOX_URI, projection, selection, null, null)?.use { cursor ->
				cursor.count
			} ?: 0
		} catch (e: Exception) {
			Log.e(TAG, "Failed to query MMS count", e)
			0
		}
	}

	private fun processMmsChange() {
		val currentCount = queryCurrentMmsCount()
		if (currentCount <= lastMmsCount) {
			return
		}
		lastMmsCount = currentCount

		val projection = arrayOf(Telephony.Mms._ID, Telephony.Mms.SUBJECT)
		val selection = "${Telephony.Mms.MESSAGE_BOX} = ${Telephony.Mms.MESSAGE_BOX_INBOX}"
		val sortOrder = "${Telephony.Mms.DATE} DESC LIMIT 1"

		try {
			contentResolver.query(MMS_INBOX_URI, projection, selection, null, sortOrder)?.use { cursor ->
				if (cursor.moveToFirst()) {
					val idCol = cursor.getColumnIndexOrThrow(Telephony.Mms._ID)
					val mmsId = cursor.getInt(idCol)
					extractAndSendMms(mmsId)
				}
			}
		} catch (e: Exception) {
			Log.e(TAG, "Error processing MMS change", e)
		}
	}

	private fun extractAndSendMms(mmsId: Int) {
		var textMessage = ""
		var mediaData: ByteArray? = null
		var fileName = ""
		var fileMimeType = ""
		var messageType = MatrixHelper.MESSAGE_TYPE_TEXT

		// 1. Query MMS Parts
		val partSelection = "${Telephony.Mms.Part.MSG_ID} = ?"
		val partArgs = arrayOf(mmsId.toString())

		contentResolver.query(MMS_PART_URI, null, partSelection, partArgs, null)?.use { partCursor ->
			val ctIndex = partCursor.getColumnIndex(Telephony.Mms.Part.CONTENT_TYPE)
			val idIndex = partCursor.getColumnIndex(Telephony.Mms.Part._ID)
			val nameIndex = partCursor.getColumnIndex(Telephony.Mms.Part.NAME)
			val textIndex = partCursor.getColumnIndex(Telephony.Mms.Part.TEXT)

			while (partCursor.moveToNext()) {
				val contentType = if (ctIndex >= 0) partCursor.getString(ctIndex) ?: "" else ""
				val partId = if (idIndex >= 0) partCursor.getString(idIndex) ?: "" else ""
				val partName = if (nameIndex >= 0) partCursor.getString(nameIndex) ?: "" else ""

				if (contentType.equals("text/plain", ignoreCase = true)) {
					val rawData = readPartBytes(partId)
					textMessage = if (rawData.isNotEmpty()) {
						String(rawData)
					} else if (textIndex >= 0) {
						partCursor.getString(textIndex) ?: ""
					} else {
						""
					}
					messageType = MatrixHelper.MESSAGE_TYPE_TEXT
				} else if (isImageType(contentType) || isVideoType(contentType)) {
					fileMimeType = contentType
					fileName = if (partName.isNotEmpty()) partName else "mms_attachment_${partId}"
					mediaData = readPartBytes(partId)
					messageType = if (isImageType(contentType)) {
						MatrixHelper.MESSAGE_TYPE_IMAGE
					} else {
						MatrixHelper.MESSAGE_TYPE_VIDEO
					}
				}
			}
		}

		// 2. Query Sender Address
		val addressUri = Uri.parse("content://mms/$mmsId/addr")
		val addrSelection = "${Telephony.Mms.Addr.TYPE} = 137" // PduHeaders.FROM
		var senderAddress = ""

		contentResolver.query(addressUri, null, addrSelection, null, null)?.use { addrCursor ->
			val addressIndex = addrCursor.getColumnIndex(Telephony.Mms.Addr.ADDRESS)
			if (addrCursor.moveToFirst() && addressIndex >= 0) {
				senderAddress = addrCursor.getString(addressIndex) ?: ""
			}
		}

		if (senderAddress.isBlank()) {
			return
		}

		// 3. Dispatch to Matrix Bridge
		if (textMessage.isNotBlank()) {
			Utilities.sendMatrix(context, textMessage, senderAddress, messageType)
		}
		if (mediaData != null && mediaData.isNotEmpty()) {
			Utilities.sendMatrix(context, mediaData, senderAddress, messageType, fileName, fileMimeType)
		}
	}

	private fun readPartBytes(partId: String): ByteArray {
		val partUri = Uri.parse("content://mms/part/$partId")
		return try {
			contentResolver.openInputStream(partUri)?.use { input ->
				val buffer = ByteArrayOutputStream()
				val temp = ByteArray(1024)
				var read: Int
				while (input.read(temp).also { read = it } != -1) {
					buffer.write(temp, 0, read)
				}
				buffer.toByteArray()
			} ?: ByteArray(0)
		} catch (e: Exception) {
			Log.e(TAG, "Failed to read part data for partId=$partId", e)
			ByteArray(0)
		}
	}

	private fun isImageType(mime: String): Boolean {
		return mime.startsWith("image/", ignoreCase = true)
	}

	private fun isVideoType(mime: String): Boolean {
		return mime.startsWith("video/", ignoreCase = true)
	}
}
