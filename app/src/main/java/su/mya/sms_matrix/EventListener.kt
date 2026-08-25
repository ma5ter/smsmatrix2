package su.mya.sms_matrix

import android.util.Log
import org.matrix.android.sdk.api.session.LiveEventListener
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.crypto.MXCryptoError
import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.api.util.JsonDict

/**
 * Migrated EventListener for matrix-android-sdk2.
 *
 * Register it with:
 * session.eventStreamService().addEventStreamListener(this)
 * (or the equivalent method available in your SDK version)
 *
 * Call session.open() / startSync() as usual in your Matrix wrapper.
 */
class EventListener(
	private val mx: MatrixHelper,          // your own wrapper class
	private val session: Session     // the real Session from matrix-android-sdk2
) : LiveEventListener {

	private var loaded = false

	// ---------------------------------------------------------------
	// Live events (replaces the old onLiveEvent)
	// ---------------------------------------------------------------
	override fun onLiveEvent(roomId: String, event: Event) {
		if (loaded) {
			// Keep the same behaviour as before
//			mx.sendEvent(event)
		}
		Log.e(TAG, "onLiveEvent: roomId=$roomId  event=$event")
	}

	// ---------------------------------------------------------------
	// To-device events
	// ---------------------------------------------------------------
	override fun onLiveToDeviceEvent(event: Event) {
		// Optional – previously you had an empty onToDeviceEvent
		Log.d(TAG, "onLiveToDeviceEvent: $event")
	}

	// ---------------------------------------------------------------
	// Decryption related
	// ---------------------------------------------------------------
	override fun onEventDecrypted(event: Event, clearEvent: JsonDict) {
		// Optional
	}

	override fun onEventDecryptionError(event: Event, cryptoError: MXCryptoError) {
		Log.w(TAG, "Decryption error: $cryptoError")
	}

	// ---------------------------------------------------------------
	// Paginated (historical) events – not used by the old code
	// ---------------------------------------------------------------
	override fun onPaginatedEvent(roomId: String, event: Event) {
		// leave empty
	}

	companion object {
		private const val TAG = "EventListener"
	}
}