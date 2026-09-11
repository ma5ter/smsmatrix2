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
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.matrix.android.sdk.api.Matrix
import org.matrix.android.sdk.api.MatrixConfiguration
import org.matrix.android.sdk.api.auth.data.HomeServerConnectionConfig
import org.matrix.android.sdk.api.failure.Failure
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.content.ContentAttachmentData
import org.matrix.android.sdk.api.session.crypto.crosssigning.DeviceTrustResult
import org.matrix.android.sdk.api.session.crypto.crosssigning.isVerified
import org.matrix.android.sdk.api.session.crypto.verification.CancelCode
import org.matrix.android.sdk.api.session.crypto.verification.EVerificationState
import org.matrix.android.sdk.api.session.crypto.verification.PendingVerificationRequest
import org.matrix.android.sdk.api.session.crypto.verification.SasTransactionState
import org.matrix.android.sdk.api.session.crypto.verification.SasVerificationTransaction
import org.matrix.android.sdk.api.session.crypto.verification.VerificationEvent
import org.matrix.android.sdk.api.session.crypto.verification.VerificationMethod
import org.matrix.android.sdk.api.session.crypto.verification.VerificationTransaction
import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.room.Room
import org.matrix.android.sdk.api.session.room.RoomSummaryQueryParams
import org.matrix.android.sdk.api.session.room.model.Membership
import org.matrix.android.sdk.api.session.room.model.RoomSummary
import org.matrix.android.sdk.api.session.room.model.message.MessageType
import java.io.File
import java.io.FileOutputStream
import java.util.Collections
import androidx.core.net.toUri
import androidx.core.content.edit
import kotlin.time.Duration.Companion.milliseconds

class MatrixHelper(
	private val context: Context,
	private val homeserverUrl: String,
	private val botUsername: String,
	private val botPassword: String,
	private val realUserId: String,
	private val deviceName: String
) : VerificationHandler {

	companion object {
		private const val TAG = "MatrixHelper"
		private const val ROOM_PREFS = "matrix_room_mappings"
		const val SYSTEM_PHONE_NUMBER: String = "System"
		private const val PREF_BOT_DEVICE_ID = "bot_device_id"
		private const val PREF_IS_VERIFIED_PREFIX = "is_verified_"

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
	private var currentSasTx: SasVerificationTransaction? = null

	private val handledReadyRequestIds = Collections.synchronizedSet(HashSet<String>())
	private val deadRoomIds = Collections.synchronizedSet(HashSet<String>())
	private val pendingMessages = Collections.synchronizedList(mutableListOf<NotSendMesage>())
	private val processedEventIds = Collections.synchronizedSet(LinkedHashSet<String>())

	init {
		login()
	}

	// -------------------------------------------------------------------------
	// Public API
	// -------------------------------------------------------------------------

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

	fun sendFile(
		phoneNumber: String,
		body: ByteArray,
		type: String,
		fileName: String,
		contentType: String
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
				val cacheDir = File(context.cacheDir, "mms_uploads").apply { mkdirs() }
				val cacheFile = File(cacheDir, "${System.currentTimeMillis()}_$fileName")
				FileOutputStream(cacheFile).use { it.write(body) }

				val attachmentType = if (type == MESSAGE_TYPE_VIDEO) {
					ContentAttachmentData.Type.VIDEO
				} else {
					ContentAttachmentData.Type.IMAGE
				}
				val attachment = ContentAttachmentData(
					size = body.size.toLong(),
					mimeType = contentType,
					name = fileName,
					queryUri = Uri.fromFile(cacheFile),
					type = attachmentType
				)
				room.sendService().sendMedia(
					attachment = attachment,
					compressBeforeSending = false,
					roomIds = setOf(room.roomId)
				)
				AppLogger.log("MMS attachment sent to Matrix for $phoneNumber ($fileName)", LogLevel.SUCCESS)
			} catch (t: Throwable) {
				Log.e(TAG, "Failed to send media to $phoneNumber", t)
				AppLogger.log("Failed to send MMS attachment to $phoneNumber: ${t.message}", LogLevel.ERROR)
			}
		}
	}

	fun getContactName(phoneNumber: String): String {
		val uri = Uri.withAppendedPath(
			ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
			Uri.encode(phoneNumber)
		)
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

	fun destroy() {
		try {
			VerificationStateBus.verificationHandler = null
			VerificationStateBus.reset()
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

	// -------------------------------------------------------------------------
	// VerificationHandler
	// -------------------------------------------------------------------------

	override fun onRequestVerification() {
		val s = session
		if (s == null || !s.isOpenable) {
			AppLogger.log("Matrix session is not active for verification", LogLevel.ERROR)
			VerificationStateBus.updateState(VerificationState.Error("Matrix session is not active."))
			return
		}
		if (realUserId.isBlank()) {
			AppLogger.log("Target Matrix User ID is empty", LogLevel.ERROR)
			VerificationStateBus.updateState(VerificationState.Error("Target Matrix User ID is empty."))
			return
		}
		scope.launch {
			try {
				VerificationStateBus.updateState(
					VerificationState.WaitingForPartner("Starting verification with $realUserId...")
				)
				AppLogger.log("Initiating SAS verification with $realUserId", LogLevel.INFO)

				val systemRoom = getOrCreateRoomForPhone(s, SYSTEM_PHONE_NUMBER, MESSAGE_TYPE_NOTICE)
				if (systemRoom == null) {
					AppLogger.log("Failed to obtain System room for verification", LogLevel.ERROR)
					VerificationStateBus.updateState(
						VerificationState.Error("Failed to obtain System room for verification")
					)
					return@launch
				}
				var targetRoomId = systemRoom.roomId
				saveRoomMapping(SYSTEM_PHONE_NUMBER, targetRoomId)

				try {
					s.cryptoService().verificationService().requestKeyVerificationInDMs(
						methods = listOf(VerificationMethod.SAS),
						otherUserId = realUserId,
						roomId = targetRoomId
					)
				} catch (t: Throwable) {
					AppLogger.log(
						"Failed to request SAS in room $targetRoomId: ${t.localizedMessage ?: t.message}. Recovering...",
						LogLevel.WARNING
					)
					handleDeletedRoom(s, SYSTEM_PHONE_NUMBER, targetRoomId)

					AppLogger.log("Recreating System room for verification...", LogLevel.INFO)
					val recoveredRoom = getOrCreateRoomForPhone(
						s,
						SYSTEM_PHONE_NUMBER,
						MESSAGE_TYPE_NOTICE,
						excludeRoomId = targetRoomId
					)
					val retryRoomId = recoveredRoom?.roomId
						?: getSavedRoomIdForPhone(SYSTEM_PHONE_NUMBER)
						?: s.roomService().createDirectRoom(realUserId).also {
							saveRoomMapping(SYSTEM_PHONE_NUMBER, it)
						}

					targetRoomId = retryRoomId
					saveRoomMapping(SYSTEM_PHONE_NUMBER, targetRoomId)
					AppLogger.log("Retrying SAS verification in new room $targetRoomId", LogLevel.INFO)

					s.cryptoService().verificationService().requestKeyVerificationInDMs(
						methods = listOf(VerificationMethod.SAS),
						otherUserId = realUserId,
						roomId = targetRoomId
					)
				}

				VerificationStateBus.updateState(
					VerificationState.WaitingForPartner("Verification request sent. Please accept on your Matrix client.")
				)
				AppLogger.log("Verification request sent to $realUserId in room $targetRoomId", LogLevel.SUCCESS)
			} catch (t: Throwable) {
				Log.e(TAG, "Failed to request SAS verification", t)
				AppLogger.log("Failed to request SAS verification: ${t.localizedMessage ?: t.message}", LogLevel.ERROR)
				VerificationStateBus.updateState(
					VerificationState.Error(t.localizedMessage ?: "Failed to initiate verification")
				)
			}
		}
	}

	override fun onRequestLegacyVerification() {
		val s = session
		if (s == null || !s.isOpenable) {
			VerificationStateBus.updateState(VerificationState.Error("Matrix session is not active."))
			return
		}
		if (realUserId.isBlank()) {
			VerificationStateBus.updateState(VerificationState.Error("Target Matrix User ID is empty."))
			return
		}
		scope.launch {
			try {
				VerificationStateBus.updateState(
					VerificationState.WaitingForPartner("Starting legacy verification with $realUserId...")
				)
				AppLogger.log("Initiating legacy to-device SAS verification with $realUserId", LogLevel.INFO)

				s.cryptoService().downloadKeysIfNeeded(listOf(realUserId), forceDownload = true)
				s.cryptoService().verificationService().requestDeviceVerification(
					methods = listOf(VerificationMethod.SAS),
					otherUserId = realUserId,
					otherDeviceId = null
				)
				VerificationStateBus.updateState(
					VerificationState.WaitingForPartner("Legacy verification request sent. Please accept on your Matrix client.")
				)
			} catch (t: Throwable) {
				Log.e(TAG, "Failed to request legacy SAS verification", t)
				VerificationStateBus.updateState(
					VerificationState.Error(t.localizedMessage ?: "Failed to initiate legacy verification")
				)
			}
		}
	}

	override fun onConfirmSas() {
		scope.launch {
			try {
				val tx = currentSasTx
				tx?.userHasVerifiedShortCode()
				if (tx?.state() !is SasTransactionState.Done) {
					VerificationStateBus.updateState(
						VerificationState.WaitingForPartner("Confirmed! Waiting for partner to confirm...")
					)
				}
			} catch (t: Throwable) {
				Log.e(TAG, "Failed to confirm SAS", t)
				VerificationStateBus.updateState(VerificationState.Error(t.localizedMessage ?: "Confirmation failed"))
			}
		}
	}

	override fun onCancelSas() {
		val tx = currentSasTx
		currentSasTx = null
		VerificationStateBus.reset()
		if (tx != null) {
			scope.launch {
				try {
					tx.cancel(CancelCode.User)
				} catch (e: Exception) {
					Log.e(TAG, "Failed to cancel SAS", e)
				}
			}
		}
	}

	// -------------------------------------------------------------------------
	// Login / session lifecycle
	// -------------------------------------------------------------------------

	private fun login() {
		scope.launch {
			try {
				AppLogger.setConnectionStatus(ConnectionStatus.CONNECTING, "Connecting to $homeserverUrl...")
				AppLogger.log("Initiating Matrix login for $botUsername", LogLevel.INFO)

				val homeServerConfig = HomeServerConnectionConfig.Builder()
					.withHomeServerUri(homeserverUrl.toUri())
					.build()

				val matrixConfiguration = MatrixConfiguration(
					roomDisplayNameFallbackProvider = SimpleRoomDisplayNameFallbackProvider()
				)
				val matrixInstance = Matrix(
					context = context.applicationContext,
					matrixConfiguration = matrixConfiguration
				)
				matrix = matrixInstance

				val savedDeviceId = roomPrefs.getString(PREF_BOT_DEVICE_ID, null)
				val authService = matrixInstance.authenticationService()
				val existingSession = authService.getLastAuthenticatedSession()
				val newSession = if (existingSession != null && isMatchingBotUser(existingSession.myUserId, botUsername)) {
					existingSession
				} else {
					authService.directAuthentication(
						homeServerConnectionConfig = homeServerConfig,
						matrixId = botUsername,
						password = botPassword,
						initialDeviceName = deviceName,
						deviceId = savedDeviceId
					)
				}

				newSession.sessionParams.deviceId.let { devId ->
					roomPrefs.edit { putString(PREF_BOT_DEVICE_ID, devId) }
				}

				session = newSession
				newSession.open()
				observeVerificationEvents(newSession)
				checkAndRestoreVerification(newSession)
				VerificationStateBus.verificationHandler = this@MatrixHelper
				newSession.syncService().startSync(fromForeground = true)

				AppLogger.setConnectionStatus(ConnectionStatus.CONNECTED, "Connected: ${newSession.myUserId}")
				AppLogger.log("Matrix session authenticated and sync started", LogLevel.SUCCESS)

				observeSyncStream(newSession)
				ensureSystemRoomAndNotifyReady(newSession)
				flushPendingMessages()
				UnreadSmsProcessor(context).processUnreadSms(this@MatrixHelper)

				Log.i(TAG, "Matrix session opened for ${newSession.myUserId}")
			} catch (t: Throwable) {
				AppLogger.setConnectionStatus(ConnectionStatus.ERROR, "Error: ${t.localizedMessage ?: "Login failed"}")
				AppLogger.log("Matrix login failed: ${t.localizedMessage}", LogLevel.ERROR)
				Log.e(TAG, "Matrix login failed", t)
			}
		}
	}

	// -------------------------------------------------------------------------
	// Sync observation
	// -------------------------------------------------------------------------

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

					val leftRooms = syncResponse.rooms?.leave ?: emptyMap()
					for ((roomId, _) in leftRooms) {
						val phone = roomPrefs.getString("room_$roomId", null)
						removeRoomMapping(phone, roomId)
						AppLogger.log("Room $roomId left or removed; cleaned up mapping", LogLevel.INFO)
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

	// -------------------------------------------------------------------------
	// Room management
	// -------------------------------------------------------------------------

	private suspend fun getOrCreateRoomForPhone(
		s: Session,
		phoneNumber: String,
		type: String,
		excludeRoomId: String? = null
	): Room? = roomMutex.withLock {
		val cleanPhone = phoneNumber.trim()
		val isSystem = cleanPhone.equals(SYSTEM_PHONE_NUMBER, ignoreCase = true)

		val existingRoom = getRoomByPhoneNumber(s, cleanPhone, excludeRoomId)
		if (existingRoom != null) {
			val updateResult = runCatching { updateRoomNameIfNeeded(existingRoom, cleanPhone) }
			val err = updateResult.exceptionOrNull()
			if (err != null && isRoomDeletedOrUnknownError(err)) {
				AppLogger.log(
					"Detected deleted room ${existingRoom.roomId} for $cleanPhone. Purging mapping...",
					LogLevel.WARNING
				)
				handleDeletedRoom(s, cleanPhone, existingRoom.roomId)
				return@withLock getOrCreateRoomForPhone(s, phoneNumber, type, excludeRoomId = existingRoom.roomId)
			}
			return@withLock existingRoom
		}

		if (type == MESSAGE_TYPE_NOTICE && !isSystem) {
			return@withLock null
		}
		if (realUserId.isBlank()) {
			Log.w(TAG, "Cannot get or create room for $cleanPhone: target realUserId is blank")
			return@withLock null
		}

		val roomId = if (isSystem) {
			findHealthyDirectRoomId(s, realUserId, excludeRoomId)
				?: s.roomService().createDirectRoom(realUserId)
		} else {
			s.roomService().createDirectRoom(realUserId)
		}
		saveRoomMapping(cleanPhone, roomId)

		var room = s.roomService().getRoom(roomId)
		if (room == null) {
			withTimeoutOrNull(5000L.milliseconds) {
				while (room == null && isActive) {
					delay(200.milliseconds)
					room = s.roomService().getRoom(roomId)
				}
			}
		}
		val targetRoom = room ?: run {
			Log.w(TAG, "Room $roomId created but summary not yet available in local store")
			return@withLock null
		}

		saveRoomMapping(cleanPhone, roomId)

		val initialRoomName = if (isSystem) {
			"$botUsername System"
		} else {
			val contactName = getContactName(cleanPhone).trim()
			if (contactName.isNotBlank() && !contactName.equals(botUsername, ignoreCase = true)) {
				contactName
			} else {
				cleanPhone
			}
		}

		runCatching {
			targetRoom.stateService().updateTopic(cleanPhone)
			targetRoom.stateService().updateName(initialRoomName)
		}.onFailure { t ->
			Log.w(TAG, "Non-fatal error updating room topic/name for $roomId: ${t.message}")
		}
		targetRoom
	}

	private suspend fun updateRoomNameIfNeeded(room: Room, phoneNumber: String) {
		if (phoneNumber.equals(SYSTEM_PHONE_NUMBER, ignoreCase = true)) {
			val systemRoomName = "$botUsername System"
			val currentName = room.roomSummary()?.name
			if (currentName != systemRoomName) {
				room.stateService().updateName(systemRoomName)
			}
			return
		}

		val roomId = room.roomId
		if (isRoomRenamed(roomId)) return

		val contactName = getContactName(phoneNumber).trim()
		if (contactName.isBlank() || contactName.equals(botUsername, ignoreCase = true)) return

		val currentName = room.roomSummary()?.name
		if (!currentName.isNullOrBlank() && currentName != phoneNumber && currentName != contactName) {
			markRoomAsRenamed(roomId, currentName)
			return
		}

		if (currentName != contactName) {
			try {
				room.stateService().updateName(contactName)
			} catch (t: Throwable) {
				Log.e(TAG, "Failed to update room name for $roomId", t)
			}
		}
	}

	/**
	 * Ensures the System room exists and always sends the readiness notice.
	 * If a previously mapped System room was deleted, it is purged and a fresh
	 * room is created so the "System ready" message is never lost.
	 */
	private suspend fun ensureSystemRoomAndNotifyReady(s: Session) {
		try {
			var room = getOrCreateRoomForPhone(s, SYSTEM_PHONE_NUMBER, MESSAGE_TYPE_TEXT)
			if (room == null) {
				// Force purge any stale mapping and retry once
				val staleId = getSavedRoomIdForPhone(SYSTEM_PHONE_NUMBER)
				if (!staleId.isNullOrEmpty()) {
					handleDeletedRoom(s, SYSTEM_PHONE_NUMBER, staleId)
				}
				room = getOrCreateRoomForPhone(s, SYSTEM_PHONE_NUMBER, MESSAGE_TYPE_TEXT)
			}
			if (room == null) {
				AppLogger.log("Could not obtain or recreate System room", LogLevel.ERROR)
				return
			}
			sendMessageToRoom(room, "System ready", MESSAGE_TYPE_TEXT)
			AppLogger.log("Sent 'System ready' to System room", LogLevel.SUCCESS)
		} catch (t: Throwable) {
			Log.e(TAG, "Failed to initialize system room or send ready notice", t)
			AppLogger.log("Failed to initialize system room: ${t.localizedMessage}", LogLevel.ERROR)
		}
	}

	private fun bindSystemRoom(activeSession: Session, roomId: String) {
		if (deadRoomIds.contains(roomId)) return
		saveRoomMapping(SYSTEM_PHONE_NUMBER, roomId)
		val room = activeSession.roomService().getRoom(roomId)
		if (room != null && room.roomSummary()?.membership == Membership.JOIN) {
			scope.launch {
				try {
					updateRoomNameIfNeeded(room, SYSTEM_PHONE_NUMBER)
				} catch (t: Throwable) {
					Log.w(TAG, "Failed to update system room name during bind", t)
				}
			}
		}
		AppLogger.log("Associated system chat with room $roomId", LogLevel.INFO)
	}

	private fun getRoomByPhoneNumber(
		s: Session,
		number: String,
		excludeRoomId: String? = null
	): Room? {
		val cleanNumber = number.trim()
		val savedRoomId = getSavedRoomIdForPhone(cleanNumber)
		if (!savedRoomId.isNullOrEmpty()) {
			if ((!excludeRoomId.isNullOrEmpty() && savedRoomId == excludeRoomId) ||
				deadRoomIds.contains(savedRoomId)
			) {
				removeRoomMapping(cleanNumber, savedRoomId)
				return null
			}
			val room = s.roomService().getRoom(savedRoomId)
			val membership = room?.roomSummary()?.membership
			if (room != null && (membership == Membership.JOIN || membership == null)) {
				return room
			}
			removeRoomMapping(cleanNumber, savedRoomId)
		}

		val query = RoomSummaryQueryParams.Builder().apply {
			memberships = listOf(Membership.JOIN)
		}.build()
		val summaries: List<RoomSummary> = s.roomService().getRoomSummaries(query)

		val isSystem = cleanNumber.equals(SYSTEM_PHONE_NUMBER, ignoreCase = true)
		val targetSummary = summaries.firstOrNull { summary ->
			if ((!excludeRoomId.isNullOrEmpty() && summary.roomId == excludeRoomId) ||
				deadRoomIds.contains(summary.roomId)
			) {
				return@firstOrNull false
			}
			if (isSystem) {
				summary.topic == SYSTEM_PHONE_NUMBER ||
						(summary.directUserId?.let { isMatchingUserId(it) } == true &&
								summary.topic.isBlank())
			} else {
				summary.topic == cleanNumber
			}
		} ?: return null

		saveRoomMapping(cleanNumber, targetSummary.roomId)
		return s.roomService().getRoom(targetSummary.roomId)
	}

	private fun findHealthyDirectRoomId(
		s: Session,
		otherUserId: String,
		excludeRoomId: String? = null
	): String? {
		val candidateRoomId = s.roomService().getExistingDirectRoomWithUser(otherUserId)
		if (!candidateRoomId.isNullOrEmpty() &&
			candidateRoomId != excludeRoomId &&
			!deadRoomIds.contains(candidateRoomId)
		) {
			val room = s.roomService().getRoom(candidateRoomId)
			if (room?.roomSummary()?.membership == Membership.JOIN) {
				val mappedPhone = roomPrefs.getString("room_$candidateRoomId", null)
				if (mappedPhone.isNullOrEmpty() ||
					mappedPhone.equals(SYSTEM_PHONE_NUMBER, ignoreCase = true)
				) {
					return candidateRoomId
				}
			}
		}
		return null
	}

	private suspend fun handleDeletedRoom(s: Session, phoneNumber: String?, roomId: String) {
		deadRoomIds.add(roomId)
		removeRoomMapping(phoneNumber, roomId)
		runCatching { s.roomService().leaveRoom(roomId) }
		AppLogger.log("Cleaned up mapping and state for deleted room $roomId", LogLevel.WARNING)
	}

	private fun isRoomDeletedOrUnknownError(t: Throwable): Boolean {
		val msg = (t.message ?: t.localizedMessage ?: "").lowercase()
		if (msg.contains("room of unknown version") ||
			msg.contains("non-create event") ||
			msg.contains("unknown room") ||
			msg.contains("m_not_found")
		) {
			return true
		}
		if (t is Failure.ServerError) {
			val code = t.error.code.uppercase()
			val serverMsg = t.error.message.lowercase()
			if (code == "M_NOT_FOUND" || t.httpCode == 404) return true
			if ((code == "M_UNKNOWN" || t.httpCode == 500) &&
				(serverMsg.contains("unknown version") ||
						serverMsg.contains("non-create event") ||
						serverMsg.contains("unknown room"))
			) {
				return true
			}
		}
		val cause = t.cause
		return cause != null && cause != t && isRoomDeletedOrUnknownError(cause)
	}

	// -------------------------------------------------------------------------
	// Mapping persistence helpers
	// -------------------------------------------------------------------------

	private fun saveRoomMapping(phoneNumber: String, roomId: String) {
		val cleanPhone = phoneNumber.trim()
		roomPrefs.edit(commit = true) {
			putString("phone_$cleanPhone", roomId)
				.putString("room_$roomId", cleanPhone)
		}
	}

	private fun removeRoomMapping(phoneNumber: String?, roomId: String?) {
		roomPrefs.edit(commit = true) {
			if (!phoneNumber.isNullOrBlank()) {
				remove("phone_${phoneNumber.trim()}")
			}
			if (!roomId.isNullOrBlank()) {
				val mappedPhone = roomPrefs.getString("room_$roomId", null)
				if (!mappedPhone.isNullOrBlank()) remove("phone_${mappedPhone.trim()}")
				remove("room_$roomId")
				remove("renamed_$roomId")
				remove("name_$roomId")
			}
		}
	}

	private fun getSavedRoomIdForPhone(phoneNumber: String): String? {
		return roomPrefs.getString("phone_${phoneNumber.trim()}", null)
	}

	private fun markRoomAsRenamed(roomId: String, newName: String) {
		roomPrefs.edit(commit = true) {
			putBoolean("renamed_$roomId", true)
				.putString("name_$roomId", newName)
		}
	}

	private fun isRoomRenamed(roomId: String): Boolean {
		return roomPrefs.getBoolean("renamed_$roomId", false)
	}

	private fun getSavedRoomName(roomId: String): String? {
		return roomPrefs.getString("name_$roomId", null)
	}

	// -------------------------------------------------------------------------
	// Message sending helpers
	// -------------------------------------------------------------------------

	private fun sendMessageToRoom(room: Room, body: String, type: String) {
		when (type) {
			MESSAGE_TYPE_TEXT, MESSAGE_TYPE_NOTICE -> {
				room.sendService().sendTextMessage(text = body, msgType = type)
			}
			else -> {
				room.sendService().sendTextMessage(text = body)
			}
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

	// -------------------------------------------------------------------------
	// Incoming Matrix events
	// -------------------------------------------------------------------------

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

		if (clearType == "m.key.verification.request" &&
			getSavedRoomIdForPhone(SYSTEM_PHONE_NUMBER).isNullOrEmpty()
		) {
			bindSystemRoom(s, roomId)
		}

		if (clearType == EventType.STATE_ROOM_NAME) {
			val newName = clearContent["name"] as? String ?: return
			if (newName.isNotBlank() && newName != getSavedRoomName(roomId)) {
				markRoomAsRenamed(roomId, newName)
				AppLogger.log("Room $roomId renamed to '$newName'", LogLevel.INFO)
			}
			return
		}

		val room = s.roomService().getRoom(roomId) ?: return
		val phoneNumber = room.roomSummary()?.topic?.takeIf { it.isNotBlank() }
			?: roomPrefs.getString("room_$roomId", null)
			?: return

		if (phoneNumber.equals(SYSTEM_PHONE_NUMBER, ignoreCase = true)) {
			if (clearType == EventType.MESSAGE) {
				val msgType = clearContent["msgtype"] as? String ?: MESSAGE_TYPE_TEXT
				val body = clearContent["body"] as? String
				if (!body.isNullOrBlank()) {
					sendMessageToRoom(room, "Echo: $body", msgType)
					AppLogger.log("Echoed system message: $body", LogLevel.INFO)
				}
			}
			scope.launch {
				s.roomService().markAllAsRead(listOf(roomId))
			}
			return
		}

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
				if (membership == "leave" || membership == "ban") {
					removeRoomMapping(phoneNumber, roomId)
					AppLogger.log("User left room $roomId ($phoneNumber); mapping removed", LogLevel.INFO)
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

	// -------------------------------------------------------------------------
	// Verification event handling
	// -------------------------------------------------------------------------

	private fun observeVerificationEvents(activeSession: Session) {
		scope.launch {
			try {
				activeSession.cryptoService().verificationService().requestEventFlow().collect { event ->
					when (event) {
						is VerificationEvent.RequestAdded ->
							handleVerificationRequest(activeSession, event.request)
						is VerificationEvent.RequestUpdated ->
							handleVerificationRequest(activeSession, event.request)
						is VerificationEvent.TransactionAdded ->
							handleVerificationTransaction(activeSession, event.transaction)
						is VerificationEvent.TransactionUpdated ->
							handleVerificationTransaction(activeSession, event.transaction)
					}
				}
			} catch (e: Exception) {
				Log.e(TAG, "Verification event stream collection failed", e)
			}
		}
	}

	private fun handleVerificationRequest(activeSession: Session, pr: PendingVerificationRequest) {
		if (!isMatchingUserId(pr.otherUserId)) return

		val dmRoomId = pr.roomId
		if (!dmRoomId.isNullOrEmpty() && getSavedRoomIdForPhone(SYSTEM_PHONE_NUMBER).isNullOrEmpty()) {
			bindSystemRoom(activeSession, dmRoomId)
		}

		scope.launch {
			try {
				if (pr.isIncoming && pr.state == EVerificationState.Requested) {
					AppLogger.log("Incoming verification request from $realUserId, accepting...", LogLevel.INFO)
					VerificationStateBus.updateState(
						VerificationState.WaitingForPartner("Accepting verification request from $realUserId...")
					)
					activeSession.cryptoService().verificationService().readyPendingVerification(
						methods = listOf(VerificationMethod.SAS),
						otherUserId = pr.otherUserId,
						transactionId = pr.transactionId
					)
				} else if (!pr.isIncoming &&
					pr.state == EVerificationState.Ready &&
					handledReadyRequestIds.add(pr.transactionId)
				) {
					AppLogger.log("Verification request ready, starting SAS transaction...", LogLevel.INFO)
					activeSession.cryptoService().verificationService().startKeyVerification(
						method = VerificationMethod.SAS,
						otherUserId = pr.otherUserId,
						requestId = pr.transactionId
					)
				}
			} catch (e: Exception) {
				Log.e(TAG, "Failed to handle verification request", e)
				AppLogger.log("Verification request error: ${e.message}", LogLevel.ERROR)
			}
		}
	}

	private fun handleVerificationTransaction(activeSession: Session, tx: VerificationTransaction) {
		if (tx !is SasVerificationTransaction) return
		if (!isMatchingUserId(tx.otherUserId)) return
		currentSasTx = tx

		when (val state = tx.state()) {
			is SasTransactionState.SasStarted -> {
				if (tx.isIncoming) {
					scope.launch {
						try {
							AppLogger.log("Incoming SAS transaction started, accepting...", LogLevel.INFO)
							tx.acceptVerification()
							AppLogger.log("SAS verification accepted", LogLevel.INFO)
						} catch (e: Exception) {
							Log.e(TAG, "Failed to accept SAS verification", e)
							AppLogger.log("Failed to accept SAS verification: ${e.message}", LogLevel.ERROR)
						}
					}
				}
			}
			is SasTransactionState.SasShortCodeReady -> {
				val emojis = tx.getEmojiCodeRepresentation()
				if (emojis.isNotEmpty()) {
					val items = emojis.map { representation ->
						val name = try {
							context.getString(representation.nameResId)
						} catch (_: Exception) {
							""
						}
						EmojiItem(emoji = representation.emoji, name = name)
					}
					VerificationStateBus.updateState(VerificationState.EmojisReceived(items, tx.transactionId))
					AppLogger.log("SAS short code ready (${items.size} emojis)", LogLevel.INFO)
				}
			}
			is SasTransactionState.SasMacReceived -> {
				AppLogger.log("SAS MAC received, codeConfirmed=${state.codeConfirmed}", LogLevel.INFO)
				if (!state.codeConfirmed) {
					VerificationStateBus.updateState(
						VerificationState.Error("SAS verification failed: emoji codes do not match")
					)
				}
			}
			is SasTransactionState.Done -> {
				VerificationStateBus.updateState(VerificationState.Success("Successfully verified with $realUserId!"))
				VerificationStateBus.setVerified(true)
				scope.launch {
					persistVerification(activeSession, tx)
				}
				AppLogger.log("Interactive verification completed with $realUserId", LogLevel.SUCCESS)
				currentSasTx = null
			}
			is SasTransactionState.Cancelled -> {
				val cancelName = state.cancelCode.name
				VerificationStateBus.updateState(VerificationState.Error("Verification cancelled: $cancelName"))
				AppLogger.log("Interactive verification cancelled ($cancelName)", LogLevel.WARNING)
				currentSasTx = null
			}
			else -> Unit
		}
	}

	// -------------------------------------------------------------------------
	// Cross-signing / verification persistence
	// -------------------------------------------------------------------------

	private suspend fun persistVerification(activeSession: Session, tx: SasVerificationTransaction) {
		try {
			val userKey = cleanUserId(realUserId)
			roomPrefs.edit(commit = true) {
				putBoolean("$PREF_IS_VERIFIED_PREFIX$userKey", true)
					.putString("verified_device_$userKey", tx.otherDeviceId.orEmpty())
			}

			val otherUserId = tx.otherUserId
			val userIds = listOf(otherUserId, activeSession.myUserId)
				.filter { it.isNotBlank() }
				.distinct()
			try {
				applyTrust(activeSession, otherUserId)
			} catch (e: Exception) {
				Log.w(TAG, "Failed to apply cross-signing trust, attempting forced key sync", e)
				AppLogger.log(
					"Cross-signing trust error: ${e.message}. Retrying with forced key sync...",
					LogLevel.WARNING
				)
				try {
					activeSession.cryptoService().downloadKeysIfNeeded(userIds, forceDownload = true)
					applyTrust(activeSession, otherUserId)
				} catch (retryEx: Exception) {
					Log.e(TAG, "Failed to apply cross-signing trust after forced key download", retryEx)
					AppLogger.log("Failed to apply cross-signing trust: ${retryEx.message}", LogLevel.ERROR)
				}
			}
			AppLogger.log("Verification state persisted for $realUserId", LogLevel.SUCCESS)
		} catch (e: Exception) {
			Log.e(TAG, "Failed to persist verification state", e)
			AppLogger.log("Failed to persist verification state: ${e.message}", LogLevel.ERROR)
		}
	}

	private suspend fun applyTrust(
		activeSession: Session,
		targetUserId: String
	) {
		val crossSigning = activeSession.cryptoService().crossSigningService()
		val isCrossSigningInit = runCatching { crossSigning.isCrossSigningInitialized() }.getOrDefault(false)
		if (isCrossSigningInit) {
			runCatching {
				crossSigning.trustUser(targetUserId)
				AppLogger.log("Cross-signing user trust confirmed for $targetUserId", LogLevel.INFO)
			}.onFailure { e ->
				Log.w(TAG, "Could not sign user with cross-signing: ${e.message}", e)
			}
		}
	}

	private suspend fun checkAndRestoreVerification(activeSession: Session) {
		val userKey = cleanUserId(realUserId)
		val isLocallyVerified = roomPrefs.getBoolean("$PREF_IS_VERIFIED_PREFIX$userKey", false)
		if (!isLocallyVerified) {
			VerificationStateBus.setVerified(false)
			return
		}

		val savedDevice = roomPrefs.getString("verified_device_$userKey", null)?.takeIf { it.isNotBlank() }
		val userIds = listOf(realUserId, activeSession.myUserId)
			.filter { it.isNotBlank() }
			.distinct()
		try {
			activeSession.cryptoService().downloadKeysIfNeeded(userIds, forceDownload = false)
			val crossSigning = activeSession.cryptoService().crossSigningService()
			val isCrossSigningInit = runCatching { crossSigning.isCrossSigningInitialized() }.getOrDefault(false)
			val isVerified = if (isCrossSigningInit) {
				val userTrust = runCatching {
					crossSigning.checkUserTrust(realUserId)
				}.getOrNull()
				userTrust?.isVerified() == true || runCatching {
					if (!savedDevice.isNullOrBlank()) {
						crossSigning.checkDeviceTrust(realUserId, savedDevice, locallyTrusted = true) is DeviceTrustResult.Success
					} else {
						false
					}
				}.getOrDefault(false)
			} else {
				true
			}

			VerificationStateBus.setVerified(isVerified)
			if (isVerified) {
				AppLogger.log("Restored verification trust for $realUserId", LogLevel.INFO)
			}
		} catch (e: Exception) {
			Log.w(TAG, "Could not check verification status from crypto store: ${e.message}", e)
			VerificationStateBus.setVerified(true)
		}
	}

	// -------------------------------------------------------------------------
	// Utility
	// -------------------------------------------------------------------------

	private fun isMatchingUserId(userId: String): Boolean {
		val cleanReal = realUserId.trim().removePrefix("@")
		val cleanOther = userId.trim().removePrefix("@")
		return cleanReal.equals(cleanOther, ignoreCase = true) ||
				cleanOther.startsWith("$cleanReal:") ||
				cleanReal.startsWith("$cleanOther:")
	}

	private fun isMatchingBotUser(sessionUserId: String, inputUsername: String): Boolean {
		val cleanInput = inputUsername.trim().removePrefix("@")
		val cleanSession = sessionUserId.trim().removePrefix("@")
		return cleanInput.equals(cleanSession, ignoreCase = true) ||
				cleanSession.startsWith("$cleanInput:") ||
				cleanInput.startsWith("$cleanSession:")
	}

	private fun cleanUserId(userId: String): String {
		return userId.trim().removePrefix("@").replace(":", "_")
	}
}