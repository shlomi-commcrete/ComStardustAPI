# AGENTS.md: ComStardustAPI Quick Navigation for AI Agents

## Project Overview
**ComStardustAPI** is an Android SDK library (`:stardust`) + demo app (`:app`) for communicating with Stardust field devices via **Bluetooth LE or USB UART**. The library manages device connections, messaging (text/PTT/location/SOS), file transfers, and local database sync.

---

## Architecture & Key Components

### Module Structure
- **`:stardust`** – Core SDK library (published as AAR to `mavenLocal` via `publishReleasePublicationToMavenLocal`)
- **`:app`** – Demo application consuming `:stardust` (minimal, mostly empty manifest)
- **Version**: 0.0.300 | **Min SDK**: 26 | **Compile SDK**: 34 | **Kotlin**: 1.9.22 | **Java**: 17

### Central Singleton: `DataManager`
**File**: `stardust/src/main/java/.../util/DataManager.kt`
- Implements `StardustAPI` interface (the public SDK contract)
- Lazily initializes: `ClientConnection` (BLE), `BittelUsbManager2` (USB), `StardustPackageHandler`, `PollingUtils`
- Static entry point – callers invoke `DataManager.sendMessage(ctx, pkg, text)` etc.
- Manages `fileSenders` (ConcurrentHashMap) for concurrent file transfers
- **Pattern**: Always call `requireContext(context)` first; stores context statically

### Connection Layer: Dual Transport
1. **BLE** (`ble/ClientConnection.kt` extends Nordic's `NordicBleManager`)
   - Handles GATT callbacks, characteristic notifications, writes (up to 3 retries with 500ms delay)
   - Queues messages in `mutableMessageList`, processes sequentially
   - Auto-reconnect, MTU negotiation (200), RSSI polling
   - State managed by `BleManager` (static boolean flags)

2. **USB** (`usb/BittelUsbManager2.kt` singleton)
   - Uses `UARTManager` wrapper over `com.hoho.android.usbserial`
   - Two devices: Stardust (main) + PTT audio (separate)
   - Auto-detects device type by product name; `processReceivedData()` routes to `StardustPackageUtils.handlePackageReceived()`

### Package Dispatch: State Machine
**File**: `stardust/StardustPackageHandler.kt`
- **Core method**: `dispatchPackage(context, mPackage, randomID)` – giant when-statement on `mPackage.stardustOpCode`
- **Interception**: `StardustInitConnectionHandler.onIncoming(ctx, pkg)` intercepts init-phase packets (addresses, ACKs, config) before dispatch
- **Deduplication**: Caches last package in `savedPackage`, filters exact duplicates
- **File handling**: Manages concurrent receivers in `fileReceivers` map, keyed by transport + transfer signature
- **Handlers**: `handleText()`, `handlePTT()`, `handleLocationReceived()`, `handleSOS()`, `handleDeviceFileResponse()` etc.

### Device Initialization Flow
**File**: `stardust/StardustInitConnectionHandler.kt` (object singleton)
- **State enum**: IDLE → REQUESTING_ADDRESSES → UPDATING_SMARTPHONE_ADDR → DELETING_GROUPS → ADDING_GROUPS → READING_CONFIGURATION → UPDATING_ADMIN_MODE → SUCCESS
- **Key method**: `start()` begins flow; `onIncoming(ctx, pkg)` returns true if packet consumed
- **Timeout**: Each step has 15s timeout; up to 3 retries before fail
- **Callbacks**: Via listener pattern `InitConnectionListener.onInitFailed(state, reason)` / `onInitDone(state)`
- **State change propagates to SDK**: `DataManager.getCallbacks()?.onDeviceInitialized(state)`

### Database: Room + Legacy Migration
**Files**: `room/new_db/{AppDatabase.kt, AppRepository.kt}`
- **Unified database** combines legacy 3-DB schema (chats, contacts, messages) into single `AppDatabase`
- **Tables**:
  - `app_contacts` (id, name, type: USER|DEVICE|GROUP)
  - `app_contact_user_ids`, `app_contact_group_ids`, `app_contact_devices` (1:1 relationships)
  - `app_chats_table` (ChatEntity), `chat_participants` (join table)
  - `app_messages_table` (MessageEntity with state: SENT|RECEIVED|SEEN)
- **One-time migration**: `AppRepository.migrateFromLegacyDatabases()` runs once (guarded by SharedPrefs flag), copies old data, deletes legacy DBs
- **Thread-safe**: `saveMutex` serializes message inserts; `contactsCacheMutex`, `groupIdsCacheMutex` for caching
- **Access**: Via `RepositoryProvider.appRepository(context)` singleton

### Configuration & Preferences
**File**: `util/SharedPreferencesUtil.kt` (object singleton)
- Stores: app user (`RegisterUser`), device address, licenses, audio settings, carrier defaults, admin mode
- Key prefixes: `KEY_CODEC_HANDLE_GAIN`, `KEY_ENABLE_AUTO_GAIN_CONTROL`, `KEY_LOCATION_PRIORITY` etc.
- Dual context support: primary + plugin context (for custom launchers)

---

## Developer Workflows

### Build & Publish
```bash
# Build the library (debug/release)
./gradlew :stardust:assembleRelease

# Publish to local Maven repo (~/.m2)
./gradlew :stardust:publishReleasePublicationToMavenLocal

# Run demo app
./gradlew :app:installDebug
```

### Key Testing Patterns
- **Unit tests**: Placed in `src/test/java/com/` (minimal in this repo)
- **Android tests**: `src/androidTest/java/` (integration tests with real devices)
- **Test runner**: `androidx.test.runner.AndroidJUnitRunner`
- **Room testing**: Use `@get:Rule val instantExecutorRule = InstantTaskExecutorRule()`

### Debugging
- **Logging**: Uses **Timber** (not plain Log)
  - Tags: `"InitHandler"`, `"stardust_tag"`, `"SerialInOutputManager"`, `"Ble write $randomID"`
  - Called throughout; no setup found (Timber.plant() missing – callers must initialize in app)
- **GATT**: Monitor `onConnectionStateChange()`, `onServicesDiscovered()` in `ClientConnection`
- **USB**: Check `SerialInputOutputManager.Listener.onNewData()` and `onRunError()`
- **Profiling**: Nordic BLE library handles low-level timing; USB UART has hardcoded delays

---

## Code Patterns & Conventions

### Singleton Pattern (Not Dependency Injection)
All major services are `object` singletons, not injected:
```kotlin
object DataManager : StardustAPI { }
object BleManager { var isBleConnected = false; ... }
object SharedPreferencesUtil { fun getAppUser(ctx: Context): RegisterUser? }
```
⚠️ **Gotcha**: Static context field. Always call `DataManager.requireContext(ctx)` before using.

### Coroutines & Threading
- **IO Dispatcher**: `CoroutineScope(Dispatchers.IO).launch { }` for async work (file ops, DB queries, messaging)
- **Main Dispatcher**: Used for UI updates, connection status changes
- **Default Coroutine scope**: `Scopes.getDefaultCoroutine()` (defined elsewhere in codebase)
- **Blocking operations**: Avoid on main thread; use `withContext(Dispatchers.IO) { }`

### Package/Message Wrapping
**File**: `stardust/StardustPackageUtils.kt` (not fully read, but used throughout)
- `StardustPackageUtils.getStardustPackage(context, source, destination, opCode, data)` – creates packets
- `StardustPackageUtils.handlePackageReceived(context, byteArray, randomID)` – decodes & dispatches
- Encodes: Byte arrays, int arrays, hex strings

### Callbacks Over Kotlin Flows
SDK uses callback interfaces, not reactive streams:
```kotlin
interface StardustAPICallbacks {
    fun receiveMessage(pkg: StardustAPIPackage, text: String)
    fun connectionStatusChanged(type: ConnectionType?)
    ...
}
// SDK consumer sets: stardustAPI.setCallback(myCallbacks)
```
⚠️ **Exception**: Room DAOs return `Flow<List<...>>` for chat summaries, messages (reactive).

### Error Handling: Try-Catch or Result-Wrapped
- BLE reconnect: `catch (e: SecurityException)` + fallback to Settings
- USB connect: Check `connectionStatus == true` before proceeding
- DB migration: Wrapped in `try { } catch (e)`, retries on next launch if it fails
- No custom exceptions; uses built-in types

---

## Integration Points & Data Flows

### Incoming Message Flow
1. **BLE/USB receives byte array** → `StardustPackageUtils.handlePackageReceived(ctx, byteArray, randomID)`
2. **Packet decoded** → `StardustPackage` object
3. **Init handler checks first**: `StardustInitConnectionHandler.onIncoming(ctx, pkg)` (returns true if consumed)
4. **If not init**: `StardustPackageHandler.dispatchPackage(ctx, pkg, randomID)`
5. **Dispatch routes by opcode**:
   - `SEND_MESSAGE` (text or PTT) → `handleText()` or `handlePTT()`
   - `RECEIVE_LOCATION` → `handleLocationReceived()`
   - `RECEIVE_SOS_INTERRUPT` → `handleSOS()`
   - `SEND_FILE` → `handleDeviceFileResponse()` (creates/updates FileReceiver)
   - etc.
6. **Callbacks fired**: `DataManager.getCallbacks()?.receiveMessage(pkg, text)` or similar
7. **Database saved** (async): Message inserted to Room via `AppRepository.saveMessage()`

### Outgoing Message Flow
1. **SDK consumer calls**: `stardustAPI.sendMessage(ctx, StardustAPIPackage(...), text)`
2. **DataManager.sendMessage()** → splits message, creates packets via `StardustPackageUtils`, saves DB
3. **For each packet**: `sendDataToBle(pkg)` → `ClientConnection.addMessageToQueue(pkg)`
4. **ClientConnection processes queue**: Sequential writes to GATT characteristic or USB UART
5. **ACK awaited** if `isDemandAck = true`; callback fired on success

### File Transfer
- **Send**: `sendFile(ctx, FileTransferData.Send, OnFileStatusChange)` → creates `FileSender` → chunks data → sends via BLE/USB
- **Receive**: Incoming `SEND_FILE` packets → `handleDeviceFileResponse()` → creates `FileReceiver` → accumulates chunks → completion callback

### Device Discovery & Bonding
1. **Scan**: `scanForDevice(ctx)` → `BleScanner.startScan()` → returns `MutableLiveData<List<ScanResult>>`
2. **Connect**: `connectToDevice(ctx, result)` → `ClientConnection.bondToBleDevice(device, name)`
3. **Bond**: GATT callback chain → services discovered → init handler started → state machine executes

---

## Critical Gotchas & Future Work

### Known Limitations
- **No proper Timber setup**: `Timber.plant()` call missing – set up in app's `Application.onCreate()`
- **Empty documentation**: Most `.md` files in root are stubs (INDEX, ARCHITECTURE, USAGE_GUIDE etc.)
- **Static context**: `DataManager.context` stored globally – potential memory leak in long-lived services
- **Legacy DB migration**: Runs in background task; subsequent data edits might race before migration completes
- **No Room encryption**: Passwords, keys stored in SharedPrefs without encryption (see `SecureKeyUtils`)

### Integration Checklist for New Features
- [ ] Add opcode to `StardustPackageUtils.StardustOpCode` enum
- [ ] Add handler method in `StardustPackageHandler.dispatchPackage()` when-branch
- [ ] If new message type: add `MessageType` enum value + `MessageEntity` changes
- [ ] Update `StardustAPICallbacks` interface for new callback
- [ ] Add Room entity if storing new data (inherit from `AppRepository`)
- [ ] Test BLE + USB paths (dual transport)
- [ ] Verify init handler doesn't intercept new packets unintentionally

---

## File Map for Rapid Navigation

| Purpose | File |
|---------|------|
| **SDK entry point** | `StardustAPI.kt` interface, `StardustAPIPackage.kt` data class |
| **Core implementation** | `util/DataManager.kt` (singleton) |
| **Package handling** | `stardust/StardustPackageHandler.kt`, `StardustPackageUtils.kt` |
| **Init state machine** | `stardust/StardustInitConnectionHandler.kt` |
| **BLE connection** | `ble/ClientConnection.kt`, `ble/BleManager.kt`, `ble/BleScanner.kt` |
| **USB connection** | `usb/BittelUsbManager2.kt` |
| **Database** | `room/new_db/AppDatabase.kt`, `room/new_db/AppRepository.kt` |
| **Preferences** | `util/SharedPreferencesUtil.kt` |
| **Configuration** | `util/ConfigurationUtils.kt` |
| **Carriers/Radio** | `util/CarriersUtils.kt` |
| **File transfer** | `util/FileSender.kt`, `util/FileReceiver.kt` |
| **Audio (PTT)** | `audio/RecorderUtils.kt`, `audio/PlayerUtils.kt` |
| **Location** | `location/LocationUtils.kt` |
| **Security** | `crypto/SecureKeyUtils.kt`, `security/EraseUtils.kt` |
| **SOS** | `util/SOSUtils.kt` |
| **Logging** | Throughout via `timber.log.Timber` |
| **Gradle** | `build.gradle.kts` (root), `stardust/build.gradle.kts`, `settings.gradle.kts` |

---

## Running Commands (Gradle 8.2.2, Kotlin 1.9.22)

```bash
# Assemble library
./gradlew :stardust:assembleDebug
./gradlew :stardust:assembleRelease

# Publish to ~/.m2/repository
./gradlew :stardust:publishReleasePublicationToMavenLocal

# Run app
./gradlew :app:installDebug
./gradlew :app:assembleDebug

# Check lint / test
./gradlew :stardust:lint
./gradlew :stardust:test
./gradlew :stardust:connectedAndroidTest
```

---

## Notes for Agents
- **Start with `DataManager`** to understand the public SDK surface
- **Trace `dispatchPackage()`** when understanding message flows
- **Dual transport**: Check both `ClientConnection` (BLE) and `BittelUsbManager2` (USB) for transport-specific logic
- **Room migrations**: Expect background task to run on first install; don't assume data is immediately available
- **Timber required**: Set up logging in app before using SDK in production

