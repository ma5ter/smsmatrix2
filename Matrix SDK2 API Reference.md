# Matrix Android SDK API Reference

## 1. Core & SDK Setup

### `Matrix`
Main entry point for initializing the SDK. Recommended to be managed as a singleton.
* `getUserAgent(): String`: Returns the User-Agent header value used for all requests.
* `authenticationService(): AuthenticationService`: Entry point for authentication and registration flows.
* `rawService(): RawService`: Direct HTTP request utility (bypassing session credentials).
* `debugService(): DebugService`: Diagnostics and database configuration insights.
* `lightweightSettingsStorage(): LightweightSettingsStorage`: Storage for global lightweight flags (e.g., threads support).
* `homeServerHistoryService(): HomeServerHistoryService`: Accesses previously connected homeserver URLs.
* `secureStorageService(): SecureStorageService`: Encrypts/decrypts local sensitive data using Android KeyStore.
* `getWorkerFactory(): WorkerFactory`: Custom factory for Android `WorkManager` tasks.
* `registerApiInterceptorListener(path: ApiPath, listener: ApiInterceptorListener)` / `unregisterApiInterceptorListener(...)`: Hooks into raw API request/response flows.
* `Companion.getSdkVersion(): String` / `Companion.getCryptoVersion(longFormat: Boolean): String`: SDK and crypto runtime version strings.

### `MatrixConfiguration`
Data class configuring global SDK capabilities (custom event providers, default room name fallback providers, network interceptors, custom `Proxy`, and TLS configurations).

---

## 2. Authentication & Account Creation

### `AuthenticationService`
Handles unauthenticated connections, discovery, logging in, and account creation.
* `suspend fun getLoginFlow(homeServerConnectionConfig: HomeServerConnectionConfig): LoginFlowResult`: Queries supported login methods for the target homeserver.
* `suspend fun getLoginFlowOfSession(sessionId: String): LoginFlowResult`: Queries login methods for a specific session ID.
* `fun getLoginWizard(): LoginWizard`: Returns a step-by-step handler for user login.
* `fun getRegistrationWizard(): RegistrationWizard`: Returns a step-by-step handler for account creation.
* `fun getSsoUrl(redirectUrl: String, deviceId: String?, providerId: String?, action: SSOAction): String?`: Generates an SSO redirect URL.
* `fun getFallbackUrl(forSignIn: Boolean, deviceId: String?): String?`: Returns Web fallback login/registration URL.
* `suspend fun directAuthentication(homeServerConnectionConfig: HomeServerConnectionConfig, matrixId: String, password: String, initialDeviceName: String, deviceId: String? = null): Session`: Authenticates with Matrix ID and password directly.
* `suspend fun loginUsingQrLoginToken(homeServerConnectionConfig: HomeServerConnectionConfig, loginToken: String, initialDeviceName: String? = null, deviceId: String? = null): Session`: Logs in using a QR login token (`m.login.token`).
* `suspend fun createSessionFromSso(homeServerConnectionConfig: HomeServerConnectionConfig, credentials: Credentials): Session`: Creates an authenticated session from SSO credentials.
* `suspend fun getWellKnownData(matrixId: String, homeServerConnectionConfig: HomeServerConnectionConfig?): WellknownResult`: Auto-discovers server base URL from user domain.
* `fun hasAuthenticatedSessions(): Boolean`: Returns whether an authenticated session exists.
* `fun getLastAuthenticatedSession(): Session?`: Returns the latest active session.
* `suspend fun cancelPendingLoginOrRegistration()` / `suspend fun reset()`: Resets authentication state and wizard workflows.

### `LoginWizard`
Manages logging into existing accounts.
* `suspend fun getProfileInfo(matrixId: String): LoginProfileInfo`: Resolves basic profile info (display name, avatar) before login.
* `suspend fun login(login: String, password: String, initialDeviceName: String, deviceId: String? = null): Session`: Standard username/password login.
* `suspend fun loginWithToken(loginToken: String): Session`: Logs in via a single-use token (e.g., SSO).
* `suspend fun loginCustom(data: JsonDict): Session`: Submits arbitrary authentication parameters.
* `suspend fun resetPassword(email: String)` / `suspend fun resetPasswordMailConfirmed(newPassword: String, logoutAllDevices: Boolean = true)`: Password recovery workflows.

### `RegistrationWizard`
Handles registration of new matrix accounts.
* `suspend fun getRegistrationFlow(): RegistrationResult`: Fetches the server registration stage requirements.
* `suspend fun registrationAvailable(userName: String): RegistrationAvailability`: Checks if a desired username is valid and available.
* `suspend fun createAccount(userName: String?, password: String?, initialDeviceDisplayName: String?): RegistrationResult`: Initiates account creation.
* `suspend fun performReCaptcha(response: String)` / `suspend fun acceptTerms()` / `suspend fun dummy()`: Fulfills respective registration stages.
* `suspend fun addThreePid(threePid: RegisterThreePid)` / `suspend fun handleValidateThreePid(code: String)` / `suspend fun checkIfEmailHasBeenValidated(delayMillis: Long)`: Third-party identifier validation stages.
* `fun getCurrentThreePid(): String?`: Retrieves pending ThreePID from persistent storage.

---

## 3. Session Management

### `Session`
Represents an active, authenticated Matrix connection.
* `val myUserId: String` / `val sessionId: String` / `val isOpenable: Boolean`: Session identifiers and state.
* `fun open()`: Opens the session and starts background tasks (must be called from Main thread).
* `fun close()`: Stops services and tears down the session.
* `suspend fun clearCache()`: Clears local cached session data (excluding credentials).
* `fun addListener(listener: Session.Listener)` / `fun removeListener(listener: Session.Listener)`: Listens for invites, errors, lifecycle events.
* `fun getOkHttpClient(): OkHttpClient` / `fun getAuthenticatedOkHttpClient(): OkHttpClient`: Network clients for custom media/requests.

#### Sub-Services accessed via `Session`
| Method | Interface | Purpose |
| :--- | :--- | :--- |
| `roomService()` | `RoomService` | Querying, creating, joining, and listing rooms. |
| `roomDirectoryService()` | `RoomDirectoryService` | Exploring public rooms and managing directory visibility. |
| `cryptoService()` | `CryptoService` | E2EE encryption/decryption, device keys, and verification. |
| `syncService()` | `SyncService` | Starting, pausing, and observing Matrix sync streams. |
| `userService()` | `UserService` | User profile lookup, search directory, and ignoring users. |
| `profileService()` | `ProfileService` | Updating current user display name, avatar, and linked ThreePIDs. |
| `accountService()` | `AccountService` | Password changes and account deactivation. |
| `accountDataService()` | `SessionAccountDataService` | Global and room-scoped account data manipulation. |
| `fileService()` | `FileService` | Downloading, caching, and decrypting attachments. |
| `eventService()` | `EventService` | Fetching individual events from cache or homeserver. |
| `spaceService()` | `SpaceService` | Space management, hierarchy queries, and child room associations. |
| `presenceService()` | `PresenceService` | Publishing and retrieving user presence (`ONLINE`, `UNAVAILABLE`, etc.). |
| `pushersService()` | `PushersService` | Registering/unregistering HTTP/Email pushers. |
| `pushRuleService()` | `PushRuleService` | Configuring and evaluating server-side push rules. |
| `sharedSecretStorageService()` | `SharedSecretStorageService` | Server-Side Secret Storage (SSSS) operations. |
| `identityService()` | `IdentityService` | Matrix Identity Server binding and lookup. |
| `callSignalingService()` | `CallSignalingService` | VoIP call creation, TURN server discovery, signaling listeners. |
| `mediaService()` | `MediaService` | Fetching URL previews and link metadata. |
| `contentUrlResolver()` | `ContentUrlResolver` | Resolving `mxc://` URIs to HTTP endpoints (full/thumbnails). |
| `contentScannerService()` | `ContentScannerService` | Antivirus content scanning integration. |
| `toDeviceService()` | `ToDeviceService` | Sending ephemeral messages to specific user devices. |
| `eventStreamService()` | `EventStreamService` | Listening for raw, live events from the sync stream. |
| `homeServerCapabilitiesService()` | `HomeServerCapabilitiesService` | Fetching homeserver capabilities and feature support. |
| `federationService()` | `FederationService` | Homeserver federation version queries. |
| `widgetService()` | `WidgetService` | Widget configuration and permissions. |
| `integrationManagerService()` | `IntegrationManagerService` | Integration Manager configurations and widget permissions. |
| `thirdPartyService()` | `ThirdPartyService` | Third-party protocols and AS bridges. |
| `termsService()` | `TermsService` | Querying and accepting terms of service. |
| `openIdService()` | `OpenIdService` | Generating OpenID exchange tokens. |

---

## 4. Room Management & Interactions

### `RoomService`
* `suspend fun createRoom(createRoomParams: CreateRoomParams): String`: Creates a room and returns its `roomId`.
* `suspend fun createDirectRoom(otherUserId: String): String`: Creates a direct 1:1 room.
* `suspend fun createLocalRoom(createRoomParams: CreateRoomParams): String` / `suspend fun deleteLocalRoom(roomId: String)`: Local-only rooms for draft interactions.
* `suspend fun joinRoom(roomIdOrAlias: String, reason: String? = null, viaServers: List<String> = emptyList())`: Joins a room by ID/Alias.
* `suspend fun leaveRoom(roomId: String, reason: String? = null)`: Leaves a room or rejects an invite.
* `fun getRoom(roomId: String): Room?`: Returns a `Room` interface for the specified ID.
* `fun getRoomSummary(roomIdOrAlias: String): RoomSummary?`: Returns the snapshot summary.
* `fun getRoomSummaryLive(roomId: String): LiveData<Optional<RoomSummary>>`: Live summary observer.
* `fun getRoomSummaries(queryParams: RoomSummaryQueryParams, sortOrder: RoomSortOrder = RoomSortOrder.NONE): List<RoomSummary>`: Queries cached room summaries.
* `fun getRoomSummariesLive(queryParams: RoomSummaryQueryParams, sortOrder: RoomSortOrder = RoomSortOrder.ACTIVITY): LiveData<List<RoomSummary>>`: Live query of room summaries.
* `fun getPagedRoomSummariesLive(...)`: Returns a `LiveData<PagedList<RoomSummary>>`.
* `suspend fun markAllAsRead(roomIds: List<String>)`: Marks all specified rooms as read.
* `fun getExistingDirectRoomWithUser(otherUserId: String): String?`: Finds an existing 1:1 room ID with a specific user.

### `Room`
Represents an individual room. Exposes specialized sub-services:
* `val roomId: String`: The ID of the room.
* `fun roomSummary(): RoomSummary?` / `fun getRoomSummaryLive(): LiveData<Optional<RoomSummary>>`: Access to room summary snapshot/stream.
* `fun asSpace(): Space?`: Casts room to `Space` if room type is `m.space`.
* `timelineService(): TimelineService`: Creates and manages timeline instances.
* `sendService(): SendService`: Sends messages, media, polls, reactions, and redacts events.
* `stateService(): StateService`: Manages room state (name, topic, avatar, join rules, power levels).
* `membershipService(): MembershipService`: Loads members, invites, bans, and kicks users.
* `readService(): ReadService`: Manages read receipts and read markers.
* `typingService(): TypingService`: Signals typing status to homeserver.
* `aliasService(): AliasService`: Queries and adds room aliases.
* `tagsService(): TagsService`: Adds or deletes room tags (e.g. Favorite, Low Priority).
* `relationService(): RelationService`: Reactions, edits, replies, and threads.
* `threadsService(): ThreadsService` / `threadsLocalService(): ThreadsLocalService`: Threaded conversations.
* `roomCryptoService(): RoomCryptoService`: Room-level encryption setup.
* `roomPushRuleService(): RoomPushRuleService`: Sets notification status (Mute, Mentions, All).
* `locationSharingService(): LocationSharingService`: Static and live location sharing.
* `pollHistoryService(): PollHistoryService`: Queries and synchronizes poll history.
* `reportingService(): ReportingService`: Reports events or rooms for abuse.
* `uploadsService(): UploadsService`: Lists media/attachments uploaded to the room.

---

## 5. Timeline, Messages & Relations

### `TimelineService`
* `fun createTimeline(eventId: String?, settings: TimelineSettings): Timeline`: Instantiates a timeline (live or centered around an event).
* `fun getTimelineEvent(eventId: String): TimelineEvent?` / `fun getTimelineEventLive(eventId: String): LiveData<Optional<TimelineEvent>>`: Retrieves a timeline event.
* `fun getAttachmentMessages(): List<TimelineEvent>`: Returns events containing images or videos.
* `fun getTimelineEventsRelatedTo(relationType: String, eventId: String): List<TimelineEvent>`: Returns related events (replies, edits, annotations).

### `Timeline`
* `fun start(rootThreadEventId: String? = null)` / `fun dispose()`: Lifecycle control.
* `fun paginate(direction: Direction, count: Int)` / `suspend fun awaitPaginate(direction: Direction, count: Int): List<TimelineEvent>`: Loads older (`BACKWARDS`) or newer (`FORWARDS`) events.
* `fun hasMoreToLoad(direction: Direction): Boolean`: Checks if pagination is available.
* `fun getSnapshot(): List<TimelineEvent>`: Current snapshot of loaded events.
* `fun addListener(listener: Timeline.Listener)` / `fun removeListener(listener: Timeline.Listener)`: Listens for new events, updates, and failures.

### `SendService`
* `fun sendTextMessage(text: CharSequence, msgType: String = MessageType.MSGTYPE_TEXT, autoMarkdown: Boolean = false, additionalContent: Content? = null): Cancelable`: Sends plain/markdown text.
* `fun sendFormattedTextMessage(text: String, formattedText: String, msgType: String = MessageType.MSGTYPE_TEXT, additionalContent: Content? = null): Cancelable`: Sends formatted HTML text.
* `fun sendMedia(attachment: ContentAttachmentData, compressBeforeSending: Boolean, roomIds: Set<String>, rootThreadEventId: String? = null, relatesTo: RelationDefaultContent? = null, additionalContent: Content? = null): Cancelable`: Sends single media.
* `fun sendMedias(attachments: List<ContentAttachmentData>, ...): Cancelable`: Sends multiple media items.
* `fun sendPoll(pollType: PollType, question: String, options: List<String>, additionalContent: Content? = null): Cancelable`: Publishes a poll.
* `fun voteToPoll(pollEventId: String, answerId: String, additionalContent: Content? = null): Cancelable`: Votes on a poll.
* `fun endPoll(pollEventId: String, additionalContent: Content? = null): Cancelable`: Closes a poll.
* `fun redactEvent(event: Event, reason: String?, withRelTypes: List<String>? = null, additionalContent: Content? = null): Cancelable`: Redacts an event.
* `fun resendTextMessage(localEcho: TimelineEvent): Cancelable` / `fun resendMediaMessage(localEcho: TimelineEvent): Cancelable`: Retries failed messages.
* `fun cancelSend(eventId: String)`: Cancels an outgoing local message.

### `RelationService`
* `fun sendReaction(targetEventId: String, reaction: String): Cancelable`: Sends emoji reaction.
* `suspend fun undoReaction(targetEventId: String, reaction: String): Cancelable`: Removes a reaction.
* `fun editTextMessage(targetEvent: TimelineEvent, msgType: String, newBodyText: CharSequence, newFormattedBodyText: CharSequence? = null, newBodyAutoMarkdown: Boolean, compatibilityBodyText: String = "* $newBodyText"): Cancelable`: Edits a text event.
* `fun editReply(replyToEdit: TimelineEvent, originalTimelineEvent: TimelineEvent, newBodyText: CharSequence, ...): Cancelable`: Edits an existing reply.
* `fun replyToMessage(eventReplied: TimelineEvent, replyText: CharSequence, replyFormattedText: CharSequence? = null, autoMarkdown: Boolean = false, showInThread: Boolean = false, rootThreadEventId: String? = null): Cancelable?`: Sends an in-room reply.
* `fun replyInThread(rootThreadEventId: String, replyInThreadText: CharSequence, ...): Cancelable?`: Sends a reply within a thread.
* `suspend fun fetchEditHistory(eventId: String): List<Event>`: Fetches previous revisions of an edited event.
* `fun getEventAnnotationsSummary(eventId: String): EventAnnotationsSummary?`: Aggregated reactions, edits, and poll responses.

---

## 6. End-to-End Encryption (E2EE) & Verification

### `CryptoService`
* `fun isCryptoEnabled(): Boolean`: Returns whether encryption is active.
* `fun isRoomEncrypted(roomId: String): Boolean`: Returns whether a specific room is encrypted.
* `suspend fun getMyCryptoDevice(): CryptoDeviceInfo`: Current device identity and keys.
* `suspend fun getUserDevices(userId: String): List<CryptoDeviceInfo>`: Queries cryptographic devices of a user.
* `suspend fun setDeviceVerification(trustLevel: DeviceTrustLevel, userId: String, deviceId: String)`: Manually sets trust level.
* `suspend fun downloadKeysIfNeeded(userIds: List<String>, forceDownload: Boolean = false): MXUsersDevicesMap<CryptoDeviceInfo>`: Downloads identity keys.
* `suspend fun decryptEvent(event: Event, timeline: String): MXEventDecryptionResult`: Manually decrypts an encrypted event.
* `fun crossSigningService(): CrossSigningService`: Cross-signing key management.
* `fun keysBackupService(): KeysBackupService`: Megolm key backup/restore.
* `fun verificationService(): VerificationService`: Interactive SAS & QR code verification.

### `CrossSigningService`
* `suspend fun isCrossSigningVerified(): Boolean`: Checks if own user cross-signing identity is verified.
* `suspend fun isCrossSigningInitialized(): Boolean`: Checks if cross-signing keys are configured on the server.
* `suspend fun initializeCrossSigning(uiaInterceptor: UserInteractiveAuthInterceptor?)`: Initializes master, self-signing, and user-signing keys.
* `suspend fun checkUserTrust(otherUserId: String): UserTrustResult`: Verifies if a user’s trust chain is valid.
* `suspend fun checkDeviceTrust(otherUserId: String, otherDeviceId: String, locallyTrusted: Boolean?): DeviceTrustResult`: Verifies a specific device's trust chain.
* `suspend fun trustUser(otherUserId: String)` / `suspend fun trustDevice(deviceId: String)`: Signs and uploads trust signatures.

### `VerificationService`
* `fun requestEventFlow(): Flow<VerificationEvent>`: Stream of verification request and transaction state changes.
* `suspend fun requestDeviceVerification(methods: List<VerificationMethod>, otherUserId: String, otherDeviceId: String?): PendingVerificationRequest`: Initiates verification via to-device messages.
* `suspend fun requestKeyVerificationInDMs(methods: List<VerificationMethod>, otherUserId: String, roomId: String, localId: String? = ...): PendingVerificationRequest`: Initiates verification via DM room events.
* `suspend fun requestSelfKeyVerification(methods: List<VerificationMethod>): PendingVerificationRequest`: Requests verification with another personal device.
* `suspend fun readyPendingVerification(methods: List<VerificationMethod>, otherUserId: String, transactionId: String): Boolean`: Accepts an incoming request.
* `suspend fun startKeyVerification(method: VerificationMethod, otherUserId: String, requestId: String): String?`: Begins the SAS or QR transaction.
* `suspend fun cancelVerificationRequest(otherUserId: String, transactionId: String)`: Cancels verification.

### `KeysBackupService`
* `fun getState(): KeysBackupState`: Returns the current backup engine state (`ReadyToBackUp`, `Disabled`, etc.).
* `suspend fun prepareKeysBackupVersion(password: String?, progressListener: ProgressListener?): MegolmBackupCreationInfo`: Prepares Megolm backup keys.
* `suspend fun createKeysBackupVersion(keysBackupCreationInfo: MegolmBackupCreationInfo): KeysVersion`: Creates a new backup version on the server.
* `suspend fun restoreKeysWithRecoveryKey(keysVersionResult: KeysVersionResult, recoveryKey: IBackupRecoveryKey, roomId: String?, sessionId: String?, stepProgressListener: StepProgressListener?): ImportRoomKeysResult`: Restores room keys using recovery key.
* `suspend fun restoreKeyBackupWithPassword(keysBackupVersion: KeysVersionResult, password: String, ...): ImportRoomKeysResult`: Restores room keys using password.
* `suspend fun deleteBackup(version: String)`: Deletes key backup on the server.

---

## 7. Spaces

### `SpaceService`
* `suspend fun createSpace(params: CreateSpaceParams): String`: Creates a space room.
* `fun getSpace(spaceId: String): Space?`: Accesses a `Space` instance.
* `suspend fun peekSpace(spaceId: String): SpacePeekResult`: Peeks into a space without joining.
* `suspend fun querySpaceChildren(spaceId: String, suggestedOnly: Boolean? = null, limit: Int? = null, from: String? = null, knownStateList: List<SpaceChildSummaryEvent>? = null): SpaceHierarchyData`: Queries child rooms/spaces via the server hierarchy API.
* `fun getSpaceSummariesLive(queryParams: SpaceSummaryQueryParams, sortOrder: RoomSortOrder = RoomSortOrder.NONE): LiveData<List<RoomSummary>>`: Live query of space summaries.
* `suspend fun joinSpace(spaceIdOrAlias: String, reason: String? = null, viaServers: List<String> = emptyList()): JoinSpaceResult`: Joins a space and child rooms.
* `suspend fun leaveSpace(spaceId: String, reason: String? = null)`: Leaves a space.
* `suspend fun setSpaceParent(childRoomId: String, parentSpaceId: String, canonical: Boolean, viaServers: List<String>)` / `suspend fun removeSpaceParent(childRoomId: String, parentSpaceId: String)`: Configures parent-child relationships between rooms and spaces.

---

## 8. Sync & Events

### `SyncService`
* `fun startSync(fromForeground: Boolean)` / `fun stopSync()`: Controls the live `/sync` loop.
* `fun getSyncState(): SyncState` / `fun getSyncStateLive(): LiveData<SyncState>`: Observes sync state (`Running`, `Paused`, `NoNetwork`, `InvalidToken`, etc.).
* `fun syncFlow(): SharedFlow<SyncResponse>`: Stream of raw sync responses.
* `fun getSyncRequestStateFlow(): SharedFlow<SyncRequestState>`: Stream of sync progress (Initial Sync step/percentage or Incremental Sync status).
* `fun hasAlreadySynced(): Boolean`: Returns whether the initial sync has finished.
* `fun startAutomaticBackgroundSync(timeOutInSeconds: Long, repeatDelayInSeconds: Long)` / `fun stopAnyBackgroundSync()`: Configures periodic background synchronization.

### `Event` (Model)
Core Matrix protocol data structure representing an event:
* Properties: `type`, `eventId`, `content`, `prevContent`, `originServerTs`, `senderId`, `stateKey`, `roomId`, `unsignedData`, `redacts`.
* `fun isEncrypted(): Boolean`: Checks if event type is `m.room.encrypted`.
* `fun getClearType(): String`: Returns decrypted event type or fallback wire type.
* `fun getClearContent(): Content?`: Returns decrypted event payload or wire content.
* `inline fun <reified T> Content?.toModel(): T?`: Deserializes JSON dictionary into Moshi data models.
* `inline fun <reified T> T.toContent(): Content`: Serializes data models into Moshi JSON dictionaries.
* Extension helpers: `isTextMessage()`, `isImageMessage()`, `isPoll()`, `isReply()`, `isThread()`, `isRedacted()`.

---

## 9. User, Presence & Profile

### `UserService`
* `fun getUser(userId: String): User?` / `fun getUserLive(userId: String): LiveData<Optional<User>>`: Retrieves cached user info.
* `suspend fun resolveUser(userId: String): User`: Resolves user from cache or fetches from profile API.
* `suspend fun searchUsersDirectory(search: String, limit: Int, excludedUserIds: Set<String>): List<User>`: Searches the global homeserver user directory.
* `fun getIgnoredUsersLive(): LiveData<List<User>>` / `suspend fun ignoreUserIds(userIds: List<String>)` / `suspend fun unIgnoreUserIds(userIds: List<String>)`: Manages ignored users list.

### `ProfileService`
* `suspend fun getDisplayName(userId: String): Optional<String>` / `suspend fun setDisplayName(userId: String, newDisplayName: String)`: Reads/updates display names.
* `suspend fun getAvatarUrl(userId: String): Optional<String>` / `suspend fun updateAvatar(userId: String, newAvatarUri: Uri, fileName: String)`: Reads/updates avatars.
* `fun getThreePids(): List<ThreePid>` / `fun getThreePidsLive(refreshData: Boolean): LiveData<List<ThreePid>>`: Returns verified ThreePIDs.
* `suspend fun addThreePid(threePid: ThreePid)` / `suspend fun submitSmsCode(threePid: ThreePid.Msisdn, code: String)` / `suspend fun finalizeAddingThreePid(threePid: ThreePid, userInteractiveAuthInterceptor: UserInteractiveAuthInterceptor)`: Adds and binds ThreePIDs.
* `suspend fun deleteThreePid(threePid: ThreePid)`: Unlinks a ThreePID from the Matrix account.

### `PresenceService`
* `suspend fun setMyPresence(presence: PresenceEnum, statusMsg: String? = null)`: Updates user presence (`ONLINE`, `OFFLINE`, `UNAVAILABLE`, `BUSY`).
* `suspend fun fetchPresence(userId: String): UserPresence`: Fetches presence state for a specific user ID.

---

## 10. Push Notifications & Push Rules

### `PushersService`
* `suspend fun addHttpPusher(httpPusher: HttpPusher)` / `fun enqueueAddHttpPusher(httpPusher: HttpPusher): UUID`: Registers an HTTP push gateway pusher.
* `suspend fun addEmailPusher(email: String, lang: String, emailBranding: String, appDisplayName: String, deviceDisplayName: String, append: Boolean = true)`: Registers an Email pusher.
* `suspend fun togglePusher(pusher: Pusher, enable: Boolean)`: Enables or disables a pusher.
* `suspend fun removePusher(pusher: Pusher)`: Unregisters a pusher.
* `suspend fun testPush(url: String, appId: String, pushkey: String, eventId: String)`: Sends a test push payload via Push Gateway.
* `fun getPushersLive(): LiveData<List<Pusher>>` / `fun getPushers(): List<Pusher>`: Queries registered pushers.

### `PushRuleService`
* `fun fetchPushRules(scope: String = RuleScope.GLOBAL)` / `fun getPushRules(scope: String = ...): RuleSet`: Retrieves push rules.
* `suspend fun updatePushRuleEnableStatus(kind: RuleKind, pushRule: PushRule, enabled: Boolean)`: Toggles individual rules.
* `suspend fun updatePushRuleActions(kind: RuleKind, ruleId: String, enable: Boolean, actions: List<Action>?)`: Updates notification actions (sound, highlight, notify).
* `fun getActions(event: Event): List<Action>`: Evaluates matched push actions for a specific event.

---

## 11. Files, Media & Permalinks

### `FileService`
* `suspend fun downloadFile(fileName: String, mimeType: String?, url: String?, elementToDecrypt: ElementToDecrypt?): File`: Downloads and decrypts a file into the local cache.
* `fun isFileInCache(mxcUrl: String?, fileName: String, mimeType: String?, elementToDecrypt: ElementToDecrypt?): Boolean`: Checks if file exists locally.
* `fun fileState(mxcUrl: String?, fileName: String, mimeType: String?, elementToDecrypt: ElementToDecrypt?): FileState`: Returns cache status (`InCache`, `Downloading`, `Unknown`).
* `fun getTemporarySharableURI(mxcUrl: String?, fileName: String, mimeType: String?, elementToDecrypt: ElementToDecrypt?): Uri?`: Generates a `FileProvider` URI for sharing.
* `fun clearCache()` / `fun clearDecryptedCache()` / `fun getCacheSize(): Long`: Cache maintenance.

### `ContentUrlResolver`
* `fun resolveFullSize(contentUrl: String?): String?`: Converts an `mxc://` URI to an HTTP download URL.
* `fun resolveThumbnail(contentUrl: String?, width: Int, height: Int, method: ThumbnailMethod): String?`: Generates an HTTP thumbnail URL.
* `fun resolveForDownload(contentUrl: String?, elementToDecrypt: ElementToDecrypt? = null): ResolvedMethod?`: Resolves URL for content scanners or direct downloads.

### `PermalinkService`
* `fun createPermalink(event: Event, forceMatrixTo: Boolean = false): String?`: Generates a permalink for an event.
* `fun createPermalink(id: String, forceMatrixTo: Boolean = false): String?`: Generates a permalink for a user ID or room ID.
* `fun createRoomPermalink(roomId: String, viaServers: List<String>? = null, forceMatrixTo: Boolean = false): String?`: Generates a room permalink with `via` routing parameters.
* `fun getLinkedId(url: String): String?`: Extracts Matrix identifier from a permalink.

### `PermalinkParser`
* `PermalinkParser.parse(uri: Uri): PermalinkData`: Parses a URI (`matrix.to`, client links) into structured data (`RoomLink`, `UserLink`, `RoomEmailInviteLink`, or `FallbackLink`).

---

## 12. Errors & UIA Interception

### `UserInteractiveAuthInterceptor`
Interface to fulfill User-Interactive Authentication (UIA) challenges when performing restricted actions (e.g., deleting devices, deactivating accounts, adding ThreePIDs):
* `fun performStage(flowResponse: RegistrationFlowResponse, errCode: String?, promise: Continuation<UIABaseAuth>)`: Prompt user for password, 2FA, or open SSO, then resumes continuation with `UIABaseAuth` credentials.

### `Failure`
Root sealed class for errors thrown by suspend methods:
* `Failure.NetworkConnection(ioException)`: Network reachability or I/O failure.
* `Failure.ServerError(error: MatrixError, httpCode: Int)`: Standard Matrix specification error.
* `Failure.OtherServerError(errorBody: String, httpCode: Int)`: Non-standard server error response.
* `Failure.UnrecognizedCertificateFailure(url, fingerprint)`: Untrusted/unrecognized SSL certificate.
* `Failure.RegistrationFlowError(registrationFlowResponse)`: Registration stage mismatch.
* `Failure.CryptoError(error: MXCryptoError)`: Encryption/Decryption failures.
* `Failure.FeatureFailure`: Domain-specific errors (`IdentityServiceError`, `CreateRoomFailure`, `JoinRoomFailure`, `WidgetManagementFailure`, etc.).