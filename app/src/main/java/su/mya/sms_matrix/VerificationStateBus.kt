package su.mya.sms_matrix

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class EmojiItem(
	val emoji: String,
	val name: String
)

sealed class VerificationState {
	object Idle : VerificationState()
	data class WaitingForPartner(val message: String) : VerificationState()
	data class EmojisReceived(val emojis: List<EmojiItem>, val transactionId: String) : VerificationState()
	data class Success(val message: String) : VerificationState()
	data class Error(val reason: String) : VerificationState()
}

interface VerificationHandler {
	fun onRequestVerification()
	fun onRequestLegacyVerification()
	fun onConfirmSas()
	fun onCancelSas()
}

object VerificationStateBus {
	private val _state = MutableStateFlow<VerificationState>(VerificationState.Idle)
	val state: StateFlow<VerificationState> = _state.asStateFlow()

	private val _isVerified = MutableStateFlow(false)
	val isVerified: StateFlow<Boolean> = _isVerified.asStateFlow()

	fun setVerified(verified: Boolean) {
		_isVerified.value = verified
	}

	var verificationHandler: VerificationHandler? = null

	fun updateState(newState: VerificationState) {
		_state.value = newState
	}

	fun requestVerification() {
		val handler = verificationHandler
		if (handler != null) {
			handler.onRequestVerification()
		} else {
			updateState(VerificationState.Error("Matrix session is not active or bridge service is stopped."))
		}
	}

	fun requestLegacyVerification() {
		val handler = verificationHandler
		if (handler != null) {
			handler.onRequestLegacyVerification()
		} else {
			updateState(VerificationState.Error("Matrix session is not active or bridge service is stopped."))
		}
	}

	fun confirmSas() {
		verificationHandler?.onConfirmSas()
	}

	fun cancelSas() {
		verificationHandler?.onCancelSas()
	}

	fun reset() {
		_state.value = VerificationState.Idle
	}
}
