package su.mya.sms_matrix

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

object Utilities {

	/**
	 * Forwards incoming text or notice messages to MatrixService via foreground start.
	 */
	@JvmStatic
	fun sendMatrix(context: Context, body: String, phone: String, type: String) {
		val intent = Intent(context, MatrixService::class.java).apply {
			putExtra("SendSms_phone", phone)
			putExtra("SendSms_body", body)
			putExtra("SendSms_type", type)
		}
		ContextCompat.startForegroundService(context, intent)
	}

	/**
	 * Forwards incoming media attachments to MatrixService via foreground start.
	 */
	@JvmStatic
	fun sendMatrix(
		context: Context,
		body: ByteArray,
		phone: String,
		type: String,
		fileName: String,
		contentType: String
	) {
		val intent = Intent(context, MatrixService::class.java).apply {
			putExtra("SendSms_phone", phone)
			putExtra("SendSms_body", body)
			putExtra("SendSms_type", type)
			putExtra("SendSms_fileName", fileName)
			putExtra("SendSms_contentType", contentType)
		}
		ContextCompat.startForegroundService(context, intent)
	}
}