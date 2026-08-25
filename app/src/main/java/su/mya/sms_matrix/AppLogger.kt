package su.mya.sms_matrix

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class LogLevel {
	INFO,
	SUCCESS,
	WARNING,
	ERROR,
	EVENT
}

enum class ConnectionStatus {
	DISCONNECTED,
	CONNECTING,
	CONNECTED,
	ERROR
}

data class LogEntry(
	val id: Long = System.nanoTime(),
	val timestamp: String = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date()),
	val message: String,
	val level: LogLevel = LogLevel.INFO
)

object AppLogger {
	private const val MAX_LOGS = 300

	private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
	val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()

	private val _connectionStatus = MutableStateFlow(ConnectionStatus.DISCONNECTED)
	val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus.asStateFlow()

	private val _statusMessage = MutableStateFlow("Disconnected")
	val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

	/**
	 * Appends an event log entry and prunes older entries when the buffer overflows.
	 */
	fun log(message: String, level: LogLevel = LogLevel.INFO) {
		val entry = LogEntry(message = message, level = level)
		val current = _logs.value.toMutableList()
		current.add(entry)
		if (current.size > MAX_LOGS) {
			current.removeAt(0)
		}
		_logs.value = current
	}

	/**
	 * Updates the current Matrix homeserver connection state.
	 */
	fun setConnectionStatus(status: ConnectionStatus, message: String = status.name) {
		_connectionStatus.value = status
		_statusMessage.value = message
	}

	/**
	 * Clears the active log history.
	 */
	fun clearLogs() {
		_logs.value = emptyList()
	}
}