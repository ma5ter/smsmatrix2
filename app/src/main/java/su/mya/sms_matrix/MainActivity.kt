package su.mya.sms_matrix

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)

		val sp = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

		setContent {
			MaterialTheme {
				Surface(
					modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background
				) {
					val permissionsLauncher = rememberLauncherForActivityResult(
						contract = ActivityResultContracts.RequestMultiplePermissions()
					) { permissions ->
						val allGranted = permissions.values.all { it }
						if (allGranted) {
							AppLogger.log("Telephony permissions granted", LogLevel.SUCCESS)
							startMatrixService()
						} else {
							AppLogger.log("Required permissions not granted", LogLevel.WARNING)
						}
					}

					MainScreen(
						initialBotUsername = sp.getString("botUsername", "").orEmpty(),
						initialBotPassword = sp.getString("botPassword", "").orEmpty(),
						initialUsername = sp.getString("username", "").orEmpty(),
						initialDevice = sp.getString("device", "").orEmpty(),
						initialHsUrl = sp.getString("hsUrl", "").orEmpty(),
						initialSyncDelay = sp.getString("syncDelay", "12") ?: "12",
						initialSyncTimeout = sp.getString("syncTimeout", "30") ?: "30",
						onSave = { botUser, botPass, user, dev, hs, delay, timeout ->
							sp.edit().putString("botUsername", botUser).putString("botPassword", botPass).putString("username", user).putString("device", dev)
								.putString("hsUrl", hs).putString("syncDelay", delay).putString("syncTimeout", timeout).apply()

							AppLogger.log("Settings saved", LogLevel.INFO)

							if (hasRequiredPermissions()) {
								startMatrixService()
							} else {
								permissionsLauncher.launch(getRequiredPermissions())
							}
						})
				}
			}
		}

		if (hasRequiredPermissions()) {
			startMatrixService()
		}
	}

	private fun hasRequiredPermissions(): Boolean {
		return getRequiredPermissions().all { permission ->
			ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
		}
	}

	private fun getRequiredPermissions(): Array<String> {
		val permissions = mutableListOf(
			Manifest.permission.READ_SMS,
			Manifest.permission.SEND_SMS,
			Manifest.permission.RECEIVE_SMS,
			Manifest.permission.READ_PHONE_STATE,
			Manifest.permission.READ_CONTACTS
		)
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
			permissions.add(Manifest.permission.POST_NOTIFICATIONS)
		}
		return permissions.toTypedArray()
	}

	private fun startMatrixService() {
		val intent = Intent(this, MatrixService::class.java)
		ContextCompat.startForegroundService(this, intent)
	}

	companion object {
		private const val PREFS_NAME = "settings"
	}
}

/**
 * Main dashboard comprising the expandable settings spoiler, system event log, and bottom Matrix status indicator.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
	initialBotUsername: String,
	initialBotPassword: String,
	initialUsername: String,
	initialDevice: String,
	initialHsUrl: String,
	initialSyncDelay: String,
	initialSyncTimeout: String,
	onSave: (String, String, String, String, String, String, String) -> Unit
) {
	val logs by AppLogger.logs.collectAsState()
	val connectionStatus by AppLogger.connectionStatus.collectAsState()
	val statusMessage by AppLogger.statusMessage.collectAsState()
	val verificationState by VerificationStateBus.state.collectAsState()

	VerificationDialog(state = verificationState)

	Scaffold(topBar = {
		TopAppBar(
			title = { Text("SMS Matrix Bridge", fontWeight = FontWeight.SemiBold) }, colors = TopAppBarDefaults.topAppBarColors(
				containerColor = MaterialTheme.colorScheme.surfaceVariant
			)
		)
	}, bottomBar = {
		ConnectionStatusBar(status = connectionStatus, message = statusMessage)
	}) { innerPadding ->
		Column(
			modifier = Modifier
				.fillMaxSize()
				.padding(innerPadding)
				.padding(horizontal = 12.dp, vertical = 8.dp)
		) {
			SettingsSpoiler(
				initialBotUsername = initialBotUsername,
				initialBotPassword = initialBotPassword,
				initialUsername = initialUsername,
				initialDevice = initialDevice,
				initialHsUrl = initialHsUrl,
				initialSyncDelay = initialSyncDelay,
				initialSyncTimeout = initialSyncTimeout,
				onSave = onSave
			)

			Spacer(modifier = Modifier.height(8.dp))

			EventLogArea(
				logs = logs, onClearLogs = { AppLogger.clearLogs() }, modifier = Modifier.weight(1f)
			)
		}
	}
}

/**
 * Expandable spoiler container holding all configuration inputs and collapsed by default.
 */
@Composable
fun SettingsSpoiler(
	initialBotUsername: String,
	initialBotPassword: String,
	initialUsername: String,
	initialDevice: String,
	initialHsUrl: String,
	initialSyncDelay: String,
	initialSyncTimeout: String,
	onSave: (String, String, String, String, String, String, String) -> Unit
) {
	var expanded by rememberSaveable { mutableStateOf(false) }

	var botUsername by remember { mutableStateOf(initialBotUsername) }
	var botPassword by remember { mutableStateOf(initialBotPassword) }
	var username by remember { mutableStateOf(initialUsername) }
	var device by remember { mutableStateOf(initialDevice) }
	var hsUrl by remember { mutableStateOf(initialHsUrl) }
	var syncDelay by remember { mutableStateOf(initialSyncDelay) }
	var syncTimeout by remember { mutableStateOf(initialSyncTimeout) }

	val isVerified by VerificationStateBus.isVerified.collectAsState()
	val arrowRotation by animateFloatAsState(targetValue = if (expanded) 180f else 0f, label = "arrow")
	val spoilerScrollState = rememberScrollState()

	Card(
		modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
	) {
		Column(modifier = Modifier.fillMaxWidth()) {
			Row(modifier = Modifier
				.fillMaxWidth()
				.clickable { expanded = !expanded }
				.padding(horizontal = 16.dp, vertical = 14.dp),
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.SpaceBetween) {
				Text(
					text = "Settings", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold
				)
				Text(
					text = if (expanded) "▲" else "▼", modifier = Modifier.rotate(arrowRotation), fontSize = 14.sp, color = MaterialTheme.colorScheme.primary
				)
			}

			AnimatedVisibility(visible = expanded) {
				Column(
					modifier = Modifier
						.fillMaxWidth()
						.heightIn(max = 420.dp)
						.verticalScroll(spoilerScrollState)
						.padding(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)
				) {
					HorizontalDivider()

					OutlinedTextField(
						value = botUsername,
						onValueChange = { botUsername = it },
						label = { Text("Bot Username") },
						placeholder = { Text("botSms") },
						modifier = Modifier.fillMaxWidth(),
						singleLine = true
					)

					OutlinedTextField(
						value = botPassword,
						onValueChange = { botPassword = it },
						label = { Text("Bot Password") },
						placeholder = { Text("Secret123") },
						visualTransformation = PasswordVisualTransformation(),
						keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
						modifier = Modifier.fillMaxWidth(),
						singleLine = true
					)

					OutlinedTextField(
						value = hsUrl,
						onValueChange = { hsUrl = it },
						label = { Text("Homeserver URL") },
						placeholder = { Text("https://matrix.org") },
						keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
						modifier = Modifier.fillMaxWidth(),
						singleLine = true
					)

					OutlinedTextField(
						value = username,
						onValueChange = { username = it },
						label = { Text("Target Matrix User ID") },
						placeholder = { Text("@user:matrix.org") },
						modifier = Modifier.fillMaxWidth(),
						singleLine = true
					)

					OutlinedTextField(
						value = device,
						onValueChange = { device = it },
						label = { Text("Device Name") },
						placeholder = { Text("SmsBridge") },
						modifier = Modifier.fillMaxWidth(),
						singleLine = true
					)

					Row(
						modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)
					) {
						OutlinedTextField(
							value = syncDelay,
							onValueChange = { syncDelay = it },
							label = { Text("Delay (s)") },
							keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
							modifier = Modifier.weight(1f),
							singleLine = true
						)
						OutlinedTextField(
							value = syncTimeout,
							onValueChange = { syncTimeout = it },
							label = { Text("Timeout (m)") },
							keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
							modifier = Modifier.weight(1f),
							singleLine = true
						)
					}

					OutlinedButton(
						onClick = { VerificationStateBus.requestVerification() },
						modifier = Modifier.fillMaxWidth()
					) {
						Text(if (isVerified) "Device Verified (In-Chat SAS)" else "Verify Device (In-Chat SAS)")
					}

					OutlinedButton(
						onClick = { VerificationStateBus.requestLegacyVerification() },
						modifier = Modifier.fillMaxWidth()
					) {
						Text("Verify Device (Legacy To-Device SAS)")
					}

					Button(
						onClick = {
							onSave(botUsername, botPassword, username, device, hsUrl, syncDelay, syncTimeout)
							expanded = false
						}, modifier = Modifier.fillMaxWidth()
					) {
						Text("Save")
					}
				}
			}
		}
	}
}

/**
 * Scrollable log viewport rendering chronological system and telephony events.
 */
@Composable
fun EventLogArea(
	logs: List<LogEntry>, onClearLogs: () -> Unit, modifier: Modifier = Modifier
) {
	val listState = rememberLazyListState()

	LaunchedEffect(logs.size) {
		if (logs.isNotEmpty()) {
			listState.animateScrollToItem(logs.size - 1)
		}
	}

	Card(
		modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(
			containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
		)
	) {
		Column(
			modifier = Modifier
				.fillMaxSize()
				.padding(12.dp)
		) {
			Row(
				modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween
			) {
				Text(
					text = "System & Event Logs (${logs.size})", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold
				)
				if (logs.isNotEmpty()) {
					TextButton(onClick = onClearLogs) {
						Text("Clear", style = MaterialTheme.typography.bodySmall)
					}
				}
			}

			HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

			if (logs.isEmpty()) {
				Box(
					modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center
				) {
					Text(
						text = "No recorded events yet", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
					)
				}
			} else {
				LazyColumn(
					state = listState, modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)
				) {
					items(logs, key = { it.id }) { log ->
						LogItemRow(log)
					}
				}
			}
		}
	}
}

/**
 * Single formatted log entry row.
 */
@Composable
fun LogItemRow(log: LogEntry) {
	val levelColor = when (log.level) {
		LogLevel.SUCCESS -> Color(0xFF2E7D32)
		LogLevel.ERROR -> Color(0xFFC62828)
		LogLevel.WARNING -> Color(0xFFEF6C00)
		LogLevel.EVENT -> Color(0xFF1565C0)
		LogLevel.INFO -> MaterialTheme.colorScheme.onSurface
	}

	Row(
		modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top
	) {
		Text(
			text = log.timestamp,
			style = MaterialTheme.typography.labelSmall,
			fontFamily = FontFamily.Monospace,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
			modifier = Modifier.padding(top = 2.dp)
		)
		Spacer(modifier = Modifier.width(6.dp))
		Text(
			text = log.message,
			style = MaterialTheme.typography.bodySmall,
			fontFamily = FontFamily.Monospace,
			color = levelColor,
			modifier = Modifier.weight(1f)
		)
	}
}

/**
 * Persistent bottom connection status indicator bar.
 */
@Composable
fun ConnectionStatusBar(status: ConnectionStatus, message: String) {
	val statusColor = when (status) {
		ConnectionStatus.CONNECTED -> Color(0xFF4CAF50)
		ConnectionStatus.CONNECTING -> Color(0xFFFF9800)
		ConnectionStatus.ERROR -> Color(0xFFF44336)
		ConnectionStatus.DISCONNECTED -> Color(0xFF9E9E9E)
	}

	Surface(
		tonalElevation = 6.dp, modifier = Modifier
			.fillMaxWidth()
			.navigationBarsPadding()
	) {
		Row(
			modifier = Modifier
				.fillMaxWidth()
				.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically
		) {
			Box(
				modifier = Modifier
					.size(12.dp)
					.background(color = statusColor, shape = CircleShape)
			)
			Spacer(modifier = Modifier.width(10.dp))
			Column {
				Text(
					text = "Matrix Status: ${status.name}", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold
				)
				Text(
					text = message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1
				)
			}
		}
	}
}

/**
 * Modal dialog coordinating the interactive SAS emoji verification flow.
 */
@Composable
fun VerificationDialog(state: VerificationState) {
	when (state) {
		is VerificationState.Idle -> Unit
		is VerificationState.WaitingForPartner -> {
			AlertDialog(
				onDismissRequest = { VerificationStateBus.cancelSas() },
				title = { Text("Matrix SAS Verification") },
				text = {
					Column(
						horizontalAlignment = Alignment.CenterHorizontally,
						modifier = Modifier.fillMaxWidth()
					) {
						CircularProgressIndicator(modifier = Modifier.padding(16.dp))
						Text(
							text = state.message,
							style = MaterialTheme.typography.bodyMedium,
							textAlign = TextAlign.Center
						)
					}
				},
				confirmButton = {},
				dismissButton = {
					TextButton(onClick = { VerificationStateBus.cancelSas() }) {
						Text("Cancel")
					}
				}
			)
		}
		is VerificationState.EmojisReceived -> {
			AlertDialog(
				onDismissRequest = { VerificationStateBus.cancelSas() },
				title = { Text("Compare Emojis") },
				text = {
					Column(
						horizontalAlignment = Alignment.CenterHorizontally,
						modifier = Modifier.fillMaxWidth()
					) {
						Text(
							text = "Verify that the emojis match those shown on your Matrix client in the same order:",
							style = MaterialTheme.typography.bodySmall,
							modifier = Modifier.padding(bottom = 12.dp)
						)
						state.emojis.chunked(4).forEach { rowEmojis ->
							Row(
								modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
								horizontalArrangement = Arrangement.SpaceEvenly
							) {
								for (item in rowEmojis) {
									Column(
										horizontalAlignment = Alignment.CenterHorizontally,
										modifier = Modifier.weight(1f)
									) {
										Text(text = item.emoji, fontSize = 28.sp)
										Text(
											text = item.name,
											style = MaterialTheme.typography.labelSmall,
											textAlign = TextAlign.Center,
											maxLines = 1
										)
									}
								}
							}
						}
					}
				},
				confirmButton = {
					Button(onClick = { VerificationStateBus.confirmSas() }) {
						Text("They Match")
					}
				},
				dismissButton = {
					TextButton(onClick = { VerificationStateBus.cancelSas() }) {
						Text("They Don't Match")
					}
				}
			)
		}
		is VerificationState.Success -> {
			AlertDialog(
				onDismissRequest = { VerificationStateBus.reset() },
				title = { Text("Verification Complete") },
				text = { Text(state.message) },
				confirmButton = {
					Button(onClick = { VerificationStateBus.reset() }) {
						Text("OK")
					}
				}
			)
		}
		is VerificationState.Error -> {
			AlertDialog(
				onDismissRequest = { VerificationStateBus.reset() },
				title = { Text("Verification Failed") },
				text = { Text(state.reason) },
				confirmButton = {
					Button(onClick = { VerificationStateBus.reset() }) {
						Text("Dismiss")
					}
				}
			)
		}
	}
}
