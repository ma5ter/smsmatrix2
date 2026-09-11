package su.mya.sms_matrix

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

class MatrixService : Service() {
	private var mx: MatrixHelper? = null
	private var mms: MMSMonitor? = null
	private var batteryMonitor: BatteryMonitor? = null

	override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
		startForegroundWithNotification()

		val sp = getSharedPreferences("settings", MODE_PRIVATE)
		val botUsername = sp.getString("botUsername", "").orEmpty()
		val botPassword = sp.getString("botPassword", "").orEmpty()
		val username = sp.getString("username", "").orEmpty()
		val device = sp.getString("device", "").orEmpty()
		val hsUrl = sp.getString("hsUrl", "").orEmpty()
		val syncDelay = sp.getString("syncDelay", "12").orEmpty()
		val syncTimeout = sp.getString("syncTimeout", "30").orEmpty()

		if (mx == null && botUsername.isNotEmpty() && botPassword.isNotEmpty() && username.isNotEmpty() && hsUrl.isNotEmpty()) {
			AppLogger.log("Starting Matrix background bridge...", LogLevel.INFO)
			mx = MatrixHelper(
				context = applicationContext,
				homeserverUrl = hsUrl,
				botUsername = botUsername,
				botPassword = botPassword,
				realUserId = username,
				deviceName = if (device.isEmpty()) "SmsBridge" else device
			)
		}

		if (intent != null && intent.hasExtra("SendSms_phone")) {
			val phone = intent.getStringExtra("SendSms_phone")
			val type = intent.getStringExtra("SendSms_type")

			if (phone != null && type != null && mx != null) {
				if (type == MatrixHelper.MESSAGE_TYPE_TEXT || type == MatrixHelper.MESSAGE_TYPE_NOTICE) {
					val body = intent.getStringExtra("SendSms_body")
					if (body != null) {
						mx?.sendMessage(phone, body, type)
						AppLogger.log("Forwarded message to Matrix ($phone)", LogLevel.SUCCESS)
					}
				} else if (type == MatrixHelper.MESSAGE_TYPE_IMAGE || type == MatrixHelper.MESSAGE_TYPE_VIDEO) {
					val body = intent.getByteArrayExtra("SendSms_body")
					val fileName = intent.getStringExtra("SendSms_fileName")
					val contentType = intent.getStringExtra("SendSms_contentType")
					if (body != null && fileName != null && contentType != null) {
						mx?.sendFile(phone, body, type, fileName, contentType)
						AppLogger.log("Forwarded media file to Matrix ($fileName)", LogLevel.SUCCESS)
					}
				}
			}
		}

		if (mms == null) {
			mms = MMSMonitor(applicationContext).apply {
				startMMSMonitoring()
				AppLogger.log("MMS Monitor active", LogLevel.INFO)
			}
		}

		if (batteryMonitor == null) {
			batteryMonitor = BatteryMonitor(applicationContext).apply {
				startBatteryMonitoring()
				AppLogger.log("Battery Monitor active", LogLevel.INFO)
			}
		}

		return START_STICKY
	}

	private fun startForegroundWithNotification() {
		createNotificationChannel(CHANNEL_ID, "SMS Matrix Bridge")

		val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
			.setOngoing(true)
			.setSmallIcon(R.drawable.ic_launcher_monochrome)
			.setContentTitle("SMS Matrix Bridge")
			.setContentText("Bridge service active")
			.setPriority(NotificationCompat.PRIORITY_LOW)
			.setCategory(Notification.CATEGORY_SERVICE)
			.build()

		ServiceCompat.startForeground(
			this,
			NOTIFICATION_ID,
			notification,
			ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
		)
	}

	private fun createNotificationChannel(channelId: String, channelName: String) {
		val chan = NotificationChannel(
			channelId,
			channelName,
			NotificationManager.IMPORTANCE_LOW
		).apply {
			lightColor = Color.BLUE
			lockscreenVisibility = Notification.VISIBILITY_PRIVATE
		}
		val manager = getSystemService(NotificationManager::class.java)
		manager?.createNotificationChannel(chan)
	}

	override fun onDestroy() {
		AppLogger.log("MatrixService stopped", LogLevel.WARNING)
		mx?.destroy()
		mx = null
		mms?.stopMMSMonitoring()
		mms = null
		batteryMonitor?.stopBatteryMonitoring()
		batteryMonitor = null
		super.onDestroy()
	}

	override fun onBind(intent: Intent?): IBinder? = null

	companion object {
		private const val CHANNEL_ID = "matrix_sync_channel"
		private const val NOTIFICATION_ID = 1
	}
}