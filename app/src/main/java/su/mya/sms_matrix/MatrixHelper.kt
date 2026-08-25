package su.mya.sms_matrix

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import android.telephony.SmsManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.matrix.android.sdk.api.Matrix
import org.matrix.android.sdk.api.MatrixConfiguration
import org.matrix.android.sdk.api.auth.data.HomeServerConnectionConfig
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.content.ContentAttachmentData
import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.room.Room
import org.matrix.android.sdk.api.session.room.RoomSummaryQueryParams
import org.matrix.android.sdk.api.session.room.model.RoomSummary
import org.matrix.android.sdk.api.session.room.model.message.MessageType
import java.io.File
import java.io.FileOutputStream
import java.util.Collections

class MatrixHelper(
	private val context: Context,
	private val homeserverUrl: String,
	private val botUsername: String,
	private val botPassword: String,
	private val realUserId: String,
	private val deviceName: String,
	syncDelay: String,
	syncTimeout: String
) {
	companion object {
		private const val TAG = "MatrixHelper"
		private const val ROOM_PREFS = "matrix_room_mappings"

		const val MESSAGE_TYPE_TEXT: String = MessageType.MSGTYPE_TEXT
		const val MESSAGE_TYPE_IMAGE: String = MessageType.MSGTYPE_IMAGE
		const val MESSAGE_TYPE_VIDEO: String = MessageType.MSGTYPE_VIDEO
		const val MESSAGE_TYPE_NOTICE: String = MessageType.MSGTYPE_NOTICE
	}

	private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
	private val roomMutex = Mutex()
	private val roomPrefs = context.getSharedPreferences(ROOM_PREFS, Context.MODE_PRIVATE)

	private var matrix: Matrix? = null
	private var session: Session? = null

	private val pendingMessages = Collections.synchronizedList(mutableListOf<NotSendMesage>())
	private val processedEventIds = Collections.synchronizedSet(LinkedHashSet<String>())
	private val syncDelaySeconds: Long = syncDelay.toLongOrNull() ?: 12L
	private val syncTimeoutMinutes: Long = syncTimeout.toLongOrNull() ?: 30L

	init {
		login()
	}

	/**
	 * Authenticates with homeserver, starts sync loop, and triggers unread SMS processing.
	 */
	private fun login() {
		scope.launch {
			try {
				AppLogger.setConnectionStatus(ConnectionStatus.CONNECTING, "Connecting to $homeserverUrl...")
				AppLogger.log("Initiating Matrix login for $botUsername", LogLevel.INFO)

				val homeServerConfig = HomeServerConnectionConfig.Builder().withHomeServerUri(Uri.parse(homeserverUrl)).build()

				val matrixConfiguration = MatrixConfiguration(
					roomDisplayNameFallbackProvider = SimpleRoomDisplayNameFallbackProvider()
				)

				val matrixInstance = Matrix(
					context = context.applicationContext, matrixConfiguration = matrixConfiguration
				)
				matrix = matrixInstance

				val authService = matrixInstance.authenticationService()
				val newSession = authService.directAuthentication(
					homeServerConnectionConfig = homeServerConfig, matrixId = botUsername, password = botPassword, initialDeviceName = deviceName
				)

				session = newSession
				newSession.open()
				newSession.syncService().startSync(fromForeground = true)

				AppLogger.setConnectionStatus(ConnectionStatus.CONNECTED, "Connected: ${newSession.myUserId}")
				AppLogger.log("Matrix session authenticated and sync started", LogLevel.SUCCESS)

				observeSyncStream(newSession)
				flushPendingMessages()

				// Ingest historical unread SMS
				UnreadSmsProcessor(context).processUnreadSms(this@MatrixHelper)

				Log.i(TAG, "Matrix session opened for ${newSession.myUserId}")
			} catch (t: Throwable) {
				AppLogger.setConnectionStatus(ConnectionStatus.ERROR, "Error: ${t.localizedMessage ?: "Login failed"}")
				AppLogger.log("Matrix login failed: ${t.localizedMessage}", LogLevel.ERROR)
				Log.e(TAG, "Matrix login failed", t)
			}
		}
	}

	/**
	 * Observes sync stream for incoming messages and invitations.
	 */
	private fun observeSyncStream(activeSession: Session) {
		scope.launch {
			try {
				var isInitialSync = !activeSession.syncService().hasAlreadySynced()

				activeSession.syncService().syncFlow().collect { syncResponse ->
					if (isInitialSync) {
						isInitialSync = false
						return@collect
					}

					val joinedRooms = syncResponse.rooms?.join ?: emptyMap()
					for ((roomId, joinedRoom) in joinedRooms) {
						val events = joinedRoom.timeline?.events ?: emptyList()
						for (event in events) {
							handleIncomingMatrixEvent(activeSession, roomId, event)
						}
					}

					val invitedRooms = syncResponse.rooms?.invite ?: emptyMap()
					for ((roomId, _) in invitedRooms) {
						scope.launch {
							try {
								activeSession.roomService().joinRoom(roomId)
								AppLogger.log("Joined invited Matrix room: $roomId", LogLevel.INFO)
							} catch (e: Exception) {
								Log.e(TAG, "Failed to auto-join invited room $roomId", e)
							}
						}
					}
				}
			} catch (e: Exception) {
				Log.e(TAG, "Sync stream collection failed", e)
			}
		}
	}

	/**
	 * Sends incoming SMS/MMS text or notice to corresponding Matrix room.
	 */
	fun sendMessage(phoneNumber: String, body: String, type: String) {
		val s = session
		if (s == null || !s.isOpenable) {
			Log.w(TAG, "Session not ready, queuing message for $phoneNumber")
			AppLogger.log("Matrix session not ready; queuing SMS for $phoneNumber", LogLevel.WARNING)
			pendingMessages.add(NotSendMesage(phoneNumber, body, type))
			return
		}

		scope.launch {
			val sent = sendTextMessageDirect(phoneNumber, body, type)
			if (!sent) {
				pendingMessages.add(NotSendMesage(phoneNumber, body, type))
			}
		}
	}

	/**
	 * Direct suspendable sending mechanism for synchronous pipeline processing.
	 */
	suspend fun sendTextMessageDirect(phoneNumber: String, body: String, type: String): Boolean {
		val s = session ?: return false
		if (!s.isOpenable) return false

		return try {
			val room = getOrCreateRoomForPhone(s, phoneNumber, type) ?: return false
			sendMessageToRoom(room, body, type)
			true
		} catch (t: Throwable) {
			Log.e(TAG, "Failed to send direct message to $phoneNumber", t)
			false
		}
	}

	/**
	 * Sends incoming MMS binary attachment to corresponding Matrix room.
	 */
	fun sendFile(
		phoneNumber: String, body: ByteArray, type: String, fileName: String, contentType: String
	) {
		val s = session
		if (s == null || !s.isOpenable) {
			Log.w(TAG, "Session not ready, skipping file $fileName for $phoneNumber")
			AppLogger.log("Matrix session not ready; MMS media dropped for $phoneNumber", LogLevel.WARNING)
			return
		}

		scope.launch {
			try {
				val room = getOrCreateRoomForPhone(s, phoneNumber, type) ?: return@launch
				val cacheFile = File(context.cacheDir, "mms_uploads").apply { mkdirs() }.let { File(it, "${System.currentTimeMillis()}_$fileName") }

				FileOutputStream(cacheFile).use { it.write(body) }

				val attachmentType = if (type == MESSAGE_TYPE_VIDEO) {
					ContentAttachmentData.Type.VIDEO
				} else {
					ContentAttachmentData.Type.IMAGE
				}

				val attachment = ContentAttachmentData(
					size = body.size.toLong(), mimeType = contentType, name = fileName, queryUri = Uri.fromFile(cacheFile), type = attachmentType
				)

				room.sendService().sendMedia(
					attachment = attachment, compressBeforeSending = false, roomIds = setOf(room.roomId)
				)
				AppLogger.log("MMS attachment sent to Matrix for $phoneNumber ($fileName)", LogLevel.SUCCESS)
			} catch (t: Throwable) {
				Log.e(TAG, "Failed to send media to $phoneNumber", t)
				AppLogger.log("Failed to send MMS attachment to $phoneNumber: ${t.message}", LogLevel.ERROR)
			}
		}
	}

	/**
	 * Finds an existing room associated with the phone number or creates a new direct room under a synchronization lock.
	 */
	private suspend fun getOrCreateRoomForPhone(s: Session, phoneNumber: String, type: String): Room? = roomMutex.withLock {
		val cleanPhone = phoneNumber.trim()
		val existingRoom = getRoomByPhoneNumber(s, cleanPhone)
		if (existingRoom != null) {
			updateRoomNameIfNeeded(existingRoom, cleanPhone)
			return@withLock existingRoom
		}

		if (type == MESSAGE_TYPE_NOTICE) {
			return@withLock null
		}

		val roomId = s.roomService().createDirectRoom(realUserId)
		val room = s.roomService().getRoom(roomId) ?: return@withLock null

		saveRoomMapping(cleanPhone, roomId)

		val contactName = getContactName(cleanPhone).trim()
		val initialRoomName = if (contactName.isNotBlank() && !contactName.equals(botUsername, ignoreCase = true)) {
			contactName
		} else {
			cleanPhone
		}

		try {
			room.stateService().updateTopic(cleanPhone)
			room.stateService().updateName(initialRoomName)
		} catch (e: Exception) {
			Log.e(TAG, "Failed to initialize room state for $roomId", e)
		}
		room
	}

	private suspend fun updateRoomNameIfNeeded(room: Room, phoneNumber: String) {
		val roomId = room.roomId
		if (isRoomRenamed(roomId)) {
			return
		}

		val contactName = getContactName(phoneNumber).trim()
		if (contactName.isBlank() || contactName.equals(botUsername, ignoreCase = true)) {
			return
		}

		val currentName = room.roomSummary()?.name
		if (!currentName.isNullOrBlank() && currentName != phoneNumber && currentName != contactName) {
			markRoomAsRenamed(roomId, currentName)
			return
		}

		if (currentName != contactName) {
			try {
				room.stateService().updateName(contactName)
			} catch (e: Exception) {
				Log.e(TAG, "Failed to update room name for $roomId", e)
			}
		}
	}

	private fun saveRoomMapping(phoneNumber: String, roomId: String) {
		val cleanPhone = phoneNumber.trim()
		roomPrefs.edit().putString("phone_$cleanPhone", roomId).putString("room_$roomId", cleanPhone).commit()
	}

	private fun getSavedRoomIdForPhone(phoneNumber: String): String? {
		return roomPrefs.getString("phone_${phoneNumber.trim()}", null)
	}

	private fun markRoomAsRenamed(roomId: String, newName: String) {
		roomPrefs.edit().putBoolean("renamed_$roomId", true).putString("name_$roomId", newName).commit()
	}

	private fun isRoomRenamed(roomId: String): Boolean {
		return roomPrefs.getBoolean("renamed_$roomId", false)
	}

	private fun getSavedRoomName(roomId: String): String? {
		return roomPrefs.getString("name_$roomId", null)
	}

	private fun sendMessageToRoom(room: Room, body: String, type: String) {
		when (type) {
			MESSAGE_TYPE_TEXT, MESSAGE_TYPE_NOTICE -> {
				room.sendService().sendTextMessage(
					text = body, msgType = type
				)
			}

			else -> {
				room.sendService().sendTextMessage(text = body)
			}
		}
	}

	private fun getRoomByPhoneNumber(s: Session, number: String): Room? {
		val cleanNumber = number.trim()
		val savedRoomId = getSavedRoomIdForPhone(cleanNumber)
		if (!savedRoomId.isNullOrEmpty()) {
			val room = s.roomService().getRoom(savedRoomId)
			if (room != null) {
				return room
			}
		}

		val query = RoomSummaryQueryParams.Builder().build()
		val summaries: List<RoomSummary> = s.roomService().getRoomSummaries(query)

		val targetSummary = summaries.firstOrNull { summary ->
			summary.topic == cleanNumber || summary.roomId == savedRoomId
		} ?: return null

		saveRoomMapping(cleanNumber, targetSummary.roomId)
		return s.roomService().getRoom(targetSummary.roomId)
	}

	private fun handleIncomingMatrixEvent(s: Session, roomId: String, event: Event) {
		val eventId = event.eventId
		if (!eventId.isNullOrEmpty()) {
			synchronized(processedEventIds) {
				if (processedEventIds.contains(eventId)) return
				processedEventIds.add(eventId)
				if (processedEventIds.size > 1000) {
					val iterator = processedEventIds.iterator()
					if (iterator.hasNext()) {
						iterator.next()
						iterator.remove()
					}
				}
			}
		}

		if (event.senderId != realUserId) return

		val clearContent = event.getClearContent() ?: return
		val clearType = event.getClearType()

		if (clearType == EventType.STATE_ROOM_NAME) {
			val newName = clearContent["name"] as? String ?: return
			if (newName.isNotBlank() && newName != getSavedRoomName(roomId)) {
				markRoomAsRenamed(roomId, newName)
				AppLogger.log("Room $roomId renamed to '$newName'", LogLevel.INFO)
			}
			return
		}

		val room = s.roomService().getRoom(roomId) ?: return
		val phoneNumber = room.roomSummary()?.topic?.takeIf { it.isNotBlank() } ?: roomPrefs.getString("room_$roomId", null) ?: return

		val smsManager = getSmsManager()

		when (clearType) {
			EventType.MESSAGE -> {
				val msgType = clearContent["msgtype"] as? String
				if (msgType == MESSAGE_TYPE_TEXT) {
					val body = clearContent["body"] as? String ?: ""
					val parts = smsManager.divideMessage(body)
					smsManager.sendMultipartTextMessage(phoneNumber, null, parts, null, null)
					AppLogger.log("Forwarded Matrix message to SMS ($phoneNumber)", LogLevel.SUCCESS)
				} else {
					val mxcUrl = clearContent["url"] as? String
					val downloadUrl = s.contentUrlResolver().resolveFullSize(mxcUrl) ?: mxcUrl
					if (!downloadUrl.isNullOrEmpty()) {
						val parts = smsManager.divideMessage(downloadUrl)
						smsManager.sendMultipartTextMessage(phoneNumber, null, parts, null, null)
						AppLogger.log("Forwarded Matrix media URL to SMS ($phoneNumber)", LogLevel.SUCCESS)
					}
				}
			}

			EventType.STATE_ROOM_MEMBER -> {
				val membership = clearContent["membership"] as? String
				if (membership == "leave") {
					scope.launch {
						try {
							s.roomService().leaveRoom(roomId)
						} catch (e: Exception) {
							Log.e(TAG, "Failed to leave room $roomId", e)
						}
					}
				}
			}
		}

		scope.launch {
			s.roomService().markAllAsRead(listOf(roomId))
		}
	}

	private fun flushPendingMessages() {
		val queue = synchronized(pendingMessages) {
			val list = ArrayList(pendingMessages)
			pendingMessages.clear()
			list
		}
		for (item in queue) {
			val phone = item.phone
			val body = item.body
			val type = item.type
			if (!phone.isNullOrBlank() && body != null && type != null) {
				sendMessage(phone, body, type)
			}
		}
	}

	@Suppress("DEPRECATION")
	private fun getSmsManager(): SmsManager {
		return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
			context.getSystemService(SmsManager::class.java)
		} else {
			SmsManager.getDefault()
		}
	}

	fun getContactName(phoneNumber: String): String {
		val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(phoneNumber))
		val projection = arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME)

		try {
			context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
				if (cursor.moveToFirst()) {
					val nameIndex = cursor.getColumnIndex(ContactsContract.PhoneLookup.DISPLAY_NAME)
					if (nameIndex >= 0) {
						val name = cursor.getString(nameIndex)
						if (!name.isNullOrEmpty()) return name
					}
				}
			}
		} catch (e: Exception) {
			Log.e(TAG, "Error looking up contact name", e)
		}
		return phoneNumber
	}

	/**
	 * Tears down active session and cancels coroutines synchronously.
	 */
	fun destroy() {
		try {
			session?.syncService()?.stopSync()
			session?.close()
			AppLogger.setConnectionStatus(ConnectionStatus.DISCONNECTED, "Disconnected")
		} catch (e: Exception) {
			Log.e(TAG, "Error closing session", e)
		} finally {
			session = null
			matrix = null
			scope.cancel()
		}
	}
}