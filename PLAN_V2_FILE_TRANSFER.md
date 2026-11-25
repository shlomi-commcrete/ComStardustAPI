# V2 File Transfer Implementation Plan

**Protocol:** ST1 (`0x53 0x54 0x31`)  
**Reference:** `Stardust_Core/docs/V2_FILE_TRANSFER_GUIDE.md`  
**Branch:** implement off `Pv2.4.1_leases`  
**PigN submodule:** `git@github.com:commcrete-ltd/PigN.git`

---

## What changes and what stays the same

| Area | V1 (STR) | V2 (ST1) change |
|---|---|---|
| Chunk size | 60 bytes | **58 bytes** |
| Header | Separate 67-byte start packet | **Header is RS slot 0 (62 bytes)** |
| N / P discovery | Only in header | **In every data packet's `meta` field** |
| Compression | gzip always | **gzip or PigN; IMAGE = raw** |
| Copy resilience | None | **Header re-sent at wire positions 3, 9, 27, 81…** |
| DB / callbacks | — | **Identical — zero changes** |
| `FileSender` loop | — | **Same loop, plus copy injection** |

---

## Phase 1 — Model / shared constants

**File:** `stardust/src/main/java/com/commcrete/stardust/util/FileUtils.kt`

### 1.1 Add `FileTransferVersion` enum

```kotlin
enum class FileTransferVersion { V1, V2 }
```

### 1.2 Add `FileEncoding` enum (V2 only)

```kotlin
enum class FileEncoding { GZIP, PIGN }
```

### 1.3 Extend `FileTransferData.Send`

Add two fields:

```kotlin
val version: FileTransferVersion = FileTransferVersion.V1,
val usePign: Boolean = false,       // V2 only; ignored for V1 and IMAGE
```

### 1.4 Add V2 constants (companion of `FileSender` or a new `FileTransferConstants`)

```kotlin
const val FILE_CHUNK_SIZE_V2   = 58
const val HEADER_COPY_BASE     = 3   // copy positions: 3^1, 3^2, 3^3 …
```

---

## Phase 2 — Send: V2 package creation

**File:** `stardust/src/main/java/com/commcrete/stardust/util/FileSender.kt`

### 2.1 Compression (new private fun)

```
compressForV2(bytes: ByteArray, fileType: FileType, usePign: Boolean): Pair<ByteArray, FileEncoding>
```

| fileType | usePign | result |
|---|---|---|
| IMAGE | any | `bytes` unchanged, `GZIP` (encoding field = 0 means "no extra") |
| FILE / CONTACT | true | try PigN → if smaller use it, else fall back to gzip |
| FILE / CONTACT | false | gzip |

Category byte = `(contentTypeBits and 0x1F) or (encodingBits shl 5)`  
Encoding bits: 0 = gzip/raw, 1 = PigN.

### 2.2 `createStartPackageV2()` — builds the 62-byte ST1 header packet

Wire layout:
```
byte[0]     = 62  (length field)
byte[1..3]  = 0x53 0x54 0x31  ("ST1")
byte[4]     = category byte  (contentType bits[4:0] | encoding bits[7:5])
byte[5]     = padding  (how many zero bytes were appended to last chunk)
byte[6..10] = file extension, ST1-encoded, 5 bytes, zero-padded
byte[11..58]= file name, ST1-encoded, 48 bytes, zero-padded
byte[59..61]= original_size, 3-byte big-endian
byte[62]    = 0  (copy = 0, fresh header)
```

This packet is **also RS slot 0** — store the 58-byte `header_vector` (bytes 4-61) as the first element in the RS input array.

### 2.3 `createPackagesV2()` — 58-byte data chunks

Same as `createPackages()` but splits compressed bytes into 58-byte chunks.  
Each chunk becomes RS slots 1 … K-1.

### 2.4 `createSparePackagesV2()` — RS over header + data

Input to RS: `[header_vector, chunk1, chunk2, … chunkK-1]` → produces N = K + P slots.  
Parity slots become RS slots K … N-1.  
Return the N-slot list; slots keep their RS index.

### 2.5 Copy packet injection in `sendNextPackage()`

After queuing a regular data packet at **wire position** `i`, check whether `i` is a power of 3 (`i == 3^k`). If so, **also queue** a copy of the header packet with `copy = k` before advancing to position `i+1`.

Wire copy-header packet is identical to the original header packet except the last byte (`copy` field) is set to `k`.

For V1 this code path is never reached (guarded by `data.version == FileTransferVersion.V2`).

### 2.6 Data packet wire format for V2

```
byte[0]     = 62
byte[1..2]  = RS index, big-endian (1-based, since slot 0 = header)
byte[3..4]  = meta, big-endian:
                odd  index → N (total RS slot count)
                even index → P (parity slot count)
byte[5..62] = 58-byte RS payload
```

### 2.7 `FileSender.sendFile()` branching

```kotlin
if (data.version == FileTransferVersion.V2) {
    val (compressed, encoding) = compressForV2(...)
    createStartPackageV2(compressed, encoding, padding)
    createPackagesV2(compressed, padding)
    createSparePackagesV2()
} else {
    // existing V1 path — untouched
    createStartPackage()
    createPackages()
    createSparePackages()
}
```

---

## Phase 3 — Receive: dispatcher

**File:** `stardust/src/main/java/com/commcrete/stardust/stardust/StardustPackageHandler.kt`

### 3.1 Extend `handleDeviceFileResponse()`

Current check (line ~603):
```kotlin
if (bytes start with "STR") → parseFileStart / parseFileStart2
```

Add before or after:
```kotlin
if (bytes[1..3] == "ST1") → handleDeviceFileResponseV2(mPackage, channel)
```

V1 path is **completely unchanged**.

### 3.2 `handleDeviceFileResponseV2()`

- Maintains its own **dedicated** `fileReceiversV2: ConcurrentHashMap<String, FileReceiverV2>` (separate from the V1 map — avoids type conflicts and keeps V1/V2 management independent)
- Parses the first 3 bytes of payload to decide header vs data packet:
  - Bytes[1..3] == `"ST1"` and last byte (`copy`) == 0 → **fresh header** → parse header_vector → create `FileReceiverV2` → call `receiver.start()`
  - Bytes[1..3] == `"ST1"` and last byte > 0 → **copy header** → find receiver by transport key → call `receiver.addCopyHeader(copyLevel, headerVector)`
  - Any other packet with length 62 → **data/parity** → find receiver by transport key → call `receiver.addDataPackage(packet)`
- One active transfer per transport key (same `activeTransferByTransport` map, prefixed key `"V2_<transportKey>"` to avoid collisions with V1)

---

## Phase 4 — Receive: `FileReceiverV2`

**New file:** `stardust/src/main/java/com/commcrete/stardust/util/FileReceiverV2.kt`

### 4.1 Constructor

```kotlin
class FileReceiverV2(
    val headerVector: ByteArray,          // 58 bytes from ST1 header packet
    val stardustPackage: StardustPackage,
    transferId: String,
    startedAtMs: Long,
    val onLastPackageReceived: (receiver: FileReceiverV2) -> Unit
)
```

Parse header_vector on construction:
- `category` byte → `contentType` (bits 4:0) + `encoding` (bits 7:5)
- `padding` byte
- `fileExtension` (5 bytes, ST1-decoded)
- `fileName` (48 bytes, ST1-decoded)
- `originalSize` (3 bytes, big-endian)

### 4.2 Lazy N/P discovery and pending buffer

N and P are **not known upfront** — the receiver learns them from the meta field of arriving data packets and must tolerate that reality.

**Pending buffer (before N is known):**
- Keep `pendingV2: ArrayDeque<ByteArray>` with a hard cap of **16 slots**
- When a data packet arrives and `total == 0` (N not yet known): push into pending buffer and return early — do NOT try to allocate storage or run RS
- Drop packets beyond slot 16 silently (the RS will cover the loss)

**Learning N — from first odd-indexed packet:**
```kotlin
if (index % 2 == 1 && total == 0 && meta > index) {
    total = meta              // N is now known
    allocSlots(total)         // allocate receivedSlots array of size N
    flushPending()            // replay all buffered packets into their proper slots
}
```
Wire packet index 1 is always the first data packet and is odd → N is known no later than the **second** wire packet.

**Learning P — from first even-indexed packet:**
```kotlin
if (index % 2 == 0 && meta >= 2 && p == 0 && total > 0) {
    p = meta
    k = total - p
}
```
Wire packet index 2 is even → K and P are known no later than the **third** wire packet.

**Edge case — P never arrives:**
If the last possible packet (`index == N-1`) arrives and `p` is still 0, mark the transfer FAILED immediately (cannot attempt Reed-Solomon without K).

### 4.3 `addDataPackage(packet: ByteArray)`

- Extract `index` from bytes[1..2] (big-endian, 1-based RS slot index)
- Extract `meta` from bytes[3..4] (big-endian)
- Apply lazy N/P discovery logic (§4.2)
- If `total == 0` after the check: push to pending buffer and return
- Store 58-byte payload at `receivedSlots[index]`
- Call `updateProgress()` and check for completion triggers

### 4.4 Completion check (same triggers as V1)

- All N slots received, **or**
- Gap timeout, **or**
- Disconnect

Call `checkData()`:
1. Find missing RS slot indices
2. `ReedSolomon.canRecover(missing, K, P)` — same library as V1
3. If recoverable: `assembleAndSave()`
4. Else: `updateFailure(FileReceiver.FileFailure.MISSING)` → late window (120 s)

### 4.5 `assembleAndSave()`

1. `ReedSolomon.decode(slots, missing)` → produces K complete slots
2. Slot 0 = header_vector (already parsed; no action)
3. Slots 1 … K-1 = file data chunks
4. Concatenate chunks, strip `padding` bytes from last chunk
5. Decompress based on `encoding`:
   - `PIGN` → PigN decompress
   - `GZIP` / raw → `decompressTextFile()` (existing util), skip for IMAGE
6. Write to disk → `saveToMessages()` → fire same `receiveFile()` / `receiveImage()` callbacks

### 4.6 State machine, DB, timeout, late window

Identical to `FileReceiver`. Re-use the same:
- `MessageEntity` state transitions (RECEIVING → RECEIVED / FAILED)
- `FileReceiver.FileFailure` enum
- Late window (120 s) logic
- `OnFileStatusChange` callbacks

---

## Phase 5 — `StardustFileStartParserV2`

**New file:** `stardust/.../model/StardustFileStartParserV2.kt`

Single function: `parseHeaderVector(packet: ByteArray): ST1HeaderData`

Reads from offset 4 (after `byte[0]=62`, `byte[1..3]="ST1"`):

```
offset 0 (abs 4):  category byte
offset 1 (abs 5):  padding
offset 2 (abs 6):  file extension, 5 bytes
offset 7 (abs 11): file name, 48 bytes
offset 55 (abs 59): original_size, 3 bytes
```

Returns a simple data class `ST1HeaderData(contentType, encoding, padding, fileExtension, fileName, originalSize)`.

---

## Phase 6 — `StardustAPI.kt` surface

**File:** `stardust/src/main/java/com/commcrete/stardust/StardustAPI.kt`

No structural changes. The two existing methods (`sendFile`, `sendImage`) accept `FileTransferData.Send` — callers opt into V2 by constructing that object with `version = FileTransferVersion.V2`.

Optionally expose a convenience method:

```kotlin
fun sendFileV2(
    data: FileTransferData.Send,
    usePign: Boolean,
    onFileStatusChange: FileSender.OnFileStatusChange
): Deferred<Boolean> = sendFile(data.copy(version = FileTransferVersion.V2, usePign = usePign), onFileStatusChange)
```

---

## Phase 0 — PigN submodule (prerequisite)

**Before any code changes:**

```bash
# From the repo root
git submodule add git@github.com:commcrete-ltd/PigN.git stardust/libs/PigN
git submodule update --init --recursive
```

Wire into the Gradle build:
- Add `include ':PigN'` / `project(':PigN').projectDir` to `settings.gradle`
- Add `implementation project(':PigN')` to `stardust/build.gradle`

The PigN library must expose at minimum:
- `PignCompressor.compress(input: ByteArray): ByteArray`
- `PignCompressor.decompress(input: ByteArray): ByteArray`

---

## File checklist

| # | File | Action |
|---|---|---|
| 0 | `settings.gradle` + `stardust/build.gradle` | Add PigN submodule dependency |
| 1 | `FileUtils.kt` | Add `FileTransferVersion`, `FileEncoding` enums; extend `FileTransferData.Send` |
| 2 | `FileSender.kt` | Add `compressForV2()`, `createStartPackageV2()`, `createPackagesV2()`, `createSparePackagesV2()`; branch in `sendFile()`; inject copy packets in `sendNextPackage()` |
| 3 | `StardustPackageHandler.kt` | Detect `"ST1"`, route to `handleDeviceFileResponseV2()`; dedicated `fileReceiversV2` map |
| 4 | `FileReceiverV2.kt` | New class — pending buffer, lazy N/P, RS decode, PigN/gzip decompress, same callbacks/DB |
| 5 | `StardustFileStartParserV2.kt` | New parser for ST1 header_vector → `ST1HeaderData` |
| 6 | `StardustAPI.kt` | Add `sendFileV2()` convenience method |

**Files that must NOT change:** `FileReceiver.kt`, `StardustFileStartParser.kt`, `StardustFileStartPackage.kt`, all V1 send paths inside `FileSender.kt`.

---

## Resolved design decisions

| Question | Answer |
|---|---|
| PigN source | Git submodule `git@github.com:commcrete-ltd/PigN.git`, compiled into the project |
| "ST1-encoded" strings | Same V2 encoding used throughout the protocol (the existing character→hex mapping from the spec appendix) |
| N/P discovery | Lazy — pending buffer (max 16) until first odd packet gives N; first even packet gives P. Edge case: P never arrives → FAILED on last packet. |
| `fileReceivers` map | Separate `fileReceiversV2: ConcurrentHashMap<String, FileReceiverV2>` in `StardustPackageHandler` |
