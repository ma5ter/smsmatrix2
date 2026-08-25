# SMS Matrix 2

SMS Matrix 2 is an Android bridge application that connects native telephony services (SMS, MMS, and phone call status) directly to the [Matrix](https://matrix.org/) decentralized communications network using `matrix-android-sdk2` and Jetpack Compose.

It operates as a background service via a dedicated Matrix bot user, relaying incoming and outgoing messages between your phone and your Matrix client in dedicated 1:1 rooms per contact or phone number.

---

## Features

- **Bi-Directional SMS Relaying**: Incoming SMS messages are forwarded to Matrix rooms. Outgoing messages sent inside the corresponding Matrix room are dispatched as native SMS messages via Android's `SmsManager` (supporting multipart messages).
- **MMS Support**: Monitors the system MMS database to extract and upload incoming multimedia attachments (images, video, and text parts) directly to the designated Matrix room.
- **Call State Notifications**: Broadcasts phone state events (incoming ringing, answered calls, ended calls) to the corresponding contact's Matrix room as notice events.
- **Automatic Room Management**:
  - Automatically creates direct 1:1 Matrix rooms with the target user for each unique phone number.
  - Automatically resolves contact names from the local address book to name rooms.
  - Detects manual room renaming and preserves custom names.
  - Sets phone numbers in the room topic for reverse resolution.
- **Unread SMS Catch-Up**: Queries unread SMS messages upon startup, forwards them to Matrix chronologically, and updates their read state in the telephony provider.
- **Auto-Start & Persistence**: Runs as an Android Foreground Service (`DATA_SYNC`) with automatic recovery on device boot (`BOOT_COMPLETED`).
- **Live Diagnostics UI**: Jetpack Compose interface providing real-time connection status monitoring and an in-memory rolling event log.

## Configuration

When launching the application for the first time, open the **Settings** panel and configure the following parameters:

| Parameter | Description | Example |
| :--- | :--- | :--- |
| **Bot Username** | Full Matrix ID or username of the dedicated bot account | `@smsbot:matrix.org` |
| **Bot Password** | Password for the bot account | `SecretPassword123` |
| **Homeserver URL** | Base URL of the Matrix homeserver | `https://matrix-client.matrix.org` |
| **Target Matrix User ID** | Your personal Matrix user ID permitted to send/receive SMS | `@user:matrix.org` |
| **Device Name** | Client device identifier shown in Matrix session manager | `SmsBridge` |
| **Delay (s)** | Sync poll delay configuration | `12` |
| **Timeout (m)** | Sync timeout duration | `30` |

## Required Permissions

The application requires the following runtime and manifest permissions to operate:

- `android.permission.RECEIVE_SMS`: Captures incoming text messages via broadcast.
- `android.permission.READ_SMS`: Reads unread SMS messages for synchronization.
- `android.permission.SEND_SMS`: Dispatches outgoing messages triggered from Matrix.
- `android.permission.READ_PHONE_STATE`: Detects phone call state changes (ringing, off-hook, idle).
- `android.permission.READ_CONTACTS`: Resolves phone numbers to local contact names for room labeling.
- `android.permission.POST_NOTIFICATIONS`: Displays the persistent foreground service notification (Android 13+).
- `android.permission.FOREGROUND_SERVICE` & `android.permission.FOREGROUND_SERVICE_DATA_SYNC`: Ensures uninterrupted background execution.
- `android.permission.RECEIVE_BOOT_COMPLETED`: Restarts the bridge service upon device reboot.

---

## Credits & Acknowledgements

This project was inspired by and builds upon concepts from [SmsMatrix](https://github.com/tijder/SmsMatrix) by tijder.
