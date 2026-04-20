# BoltShare - Real-Time File Sharing

## 1. Project Overview
BoltShare is a real-time, cross-device file sharing Android application. It allows users to quickly transfer files by generating a unique 6-character short code. Senders can pick a file and send it specifically to a receiver's active short code, with real-time progress updates.

**Platform:** Android (Min SDK 24 declared, but functionally restricted to API 29 / Android 10+ via `@RequiresApi(Build.VERSION_CODES.Q)` annotations).
**Current Scope:** Fully functional for sending and receiving up to 100MB files. It successfully generates unique anonymous identities, handles end-to-end simulated peer-to-peer file transfer natively via a cloud relay, stores downloaded files in the local `Downloads` directory, and provides capabilities to open files natively via system Intents.

---

## 2. How to Run Locally

### Prerequisites
- **Android Studio** (Koala or newer recommended).
- **Android SDK** API 36.
- **Firebase Project:** You must configure a Firebase project for this app.
   - Enable **Anonymous Authentication**.
   - Enable **Firestore** with appropriate rules.

### Steps
1. Clone this repository and open the `BoltShare` folder in Android Studio.
2. Download your `google-services.json` from your Firebase Console and place it inside the `app/` directory.
3. Sync Gradle.
4. Run the app on an emulator or physical device.

### Backend/Relay Setup
By default, the application is hardcoded to use a demo relay backend: `https://boltshare-backend.onrender.com/upload`.
To use a custom backend:
- Update the URL string in `MainViewModel.kt` -> `sendFile()` function.
- The backend simply needs to accept a `multipart/form-data` POST request and return a JSON response with a downloadable `"url"` pointing to the file.

---

## 3. Devices & OS Tested
- **Supported OS:** Android 10 (API 29) and above. The codebase relies heavily on the Storage Access Framework (SAF) and Scoped Storage (`MediaStore.Downloads`) which mandates newer Android APIs.
- **Tested On:** Android Emulators & Physical Devices.
- **Limitations:** App will crash or fail to open on devices running API 28 or lower due to hardcoded `@RequiresApi(Build.VERSION_CODES.Q)` constraints on `MainActivity` and core composables.

---

## 4. Architecture Overview

BoltShare operates on a hybrid topology. Instead of strict local network peer-to-peer (P2P), it uses a cloud relay for payload delivery and Firebase Firestore for real-time signaling.

- **Client (Android App):** Built using Kotlin, Jetpack Compose, Coroutines, and OkHttp.
- **Transport Layer:** HTTP POST Multipart requests for large payloads.
- **Signaling Layer:** Firebase Firestore manages transfer requests, statuses, and acts as a webhook-like connection between Sender and Receiver via real-time Snapshot Listeners.
- **Storage:** Local Android `Downloads/BoltShare` folder utilizing the MediaStore API.

### Flow Diagram

```text
       [Sender App]               [Firestore Signaling]              [Receiver App]
            |                              |                               |
            |--- 1. Query Receiver Code -> |                               |
            |--- 2. Create Transfer Doc -> |                               |
            |                              | <-- 3. Snapshot Listener Event|
            |                              |                               |
            |--- 4. HTTP POST Upload ----------------> [Node.js Relay Backend]
            |                              |                               |
            |--- 5. Update Status 'sent' ->|                               |
            |    & attach Download URL     | <-- 6. Snapshot Listener Event|
            |                              |                               |
            |                              |       7. HTTP GET Download ---|
```

---

## 5. Transport Choice & Rationale
- **Transport Strategy:** Explicit HTTP REST API uploads to a staging backend combined with Firebase Firestore for state management.
- **Why it was chosen:** 
  - Simplicity and speed of development. 
  - Bypasses NAT traversal constraints, firewall issues, and cross-subnet discovering problems inherent to WebRTC and WiFi-Direct architectures.
  - Allows asynchronous fetching.
- **Trade-offs:** 
  - Slower than local area network (LAN) P2P routing.
  - Incurs bandwidth costs on the third-party relay backend.
  - Less private compared to direct end-to-end device connection.

---

## 6. Edge Cases Handling

### ✅ Implemented
- **Short-code collisions:** The app queries Firestore during initialization. If a 6-character short code conflict occurs, it loops and regenerates a strictly unique, unambiguous string.
- **Invalid recipient code:** Validated proactively in `sendFile`. The system checks if the recipient code exists in Firestore before initiating an upload.
- **Large files:** Enforced mathematically with a strict 100MB UI limit. The underlying network layer natively utilizes OkHttp `BufferedSink` and stream reading to chunk bytes memory-safely instead of loading files directly into RAM. Timeout durations for stream writing are explicitly disabled.
- **Permissions:** App relies entirely on `ActivityResultContracts.OpenDocument()` (SAF) and `MediaStore`. Legitimate workarounds nullifying the need for dangerous manifest permission demands.

### ⚠️ Partially Implemented
- **Network drop mid-transfer:** Try-catch wrappers intercept IOExceptions and mark the status as `"failed"`. Real-time UI reflects failure states and exposes a `Retry Download` button. However, it lacks Range header implementation and will start downloading from 0% rather than resuming the previous byte stream.
- **Incoming transfer when app is closed:** Because Firebase Firestore holds the persistent states, a transfer sent while the app is closed *will* queue up securely. When the recipient next opens the app, the `SnapshotListener` catches up and automatically downloads pending resources. There is no active background listening agent (e.g., FCM or WorkManager) to alert a user while closed.
- **Multiple file transfers:** The receiver UI correctly queues multiple files in a LazyColumn list and handles concurrent downloads smoothly via coroutines. Conversely, the Sender is hardcoded to hold only a single `_activeSenderTransfer` state globally; sending a secondary file immediately overwrites tracking for the first.

### ❌ Not Implemented
- **Recipient offline behavior (Sender side UI):** The sender successfully uploads files to the relay backend completely agnostic of whether the recipient is physically online. There are no present indicators ensuring the receiver actively caught the payload.
- **Transfer Cancellation:** No capability to abort an ongoing upload or download (the OkHttp call handles are detached from the UI scope).

---

## 10. Future Improvements
- **Switch to Foreground Service / WorkManager:** Execute networking layers through dedicated processes to ensure large transfers don’t abruptly die when Android's OS aggressively clears the activity out of memory (App Backgrounding).
- **Implement OkHttp Call Cancellation:** Store active call execution references inside the ViewModel to allow graceful interruption/cancellation of slow uploads.
- **Local Network Discovery Backup:** Provide a local NSD (Network Service Discovery) fallback protocol to detect users on the exact same Wi-Fi point enabling local HTTP socket transfers, effectively bypassing external cloud relays for speed.
- **Range Downloads:** Adopt local chunk manifest mapping and `Range` headers to pause/resume interrupted downloads exactly where they fell off.
