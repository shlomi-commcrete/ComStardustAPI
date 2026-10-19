# File size on transfers — app integration guide

Audience: the app team integrating the Stardust SDK.

The SDK now knows a file's **original size** — its size before it was gzipped for the air —
from the transfer's **first package**, and reports it both on the live transfer and on the
message row. That lets the conversation show "2.4 MB" on an incoming file while it is still
arriving, and on a transfer that failed and left no file behind.

The SDK formats nothing: **the number is bytes, and the string is yours** (units, locale,
rounding).

---

## 1. What changed

| | Change |
|---|---|
| ✅ **New** | `FileTransferData.Receive.originalSize: Int` — on every receive callback. `0` = unknown. |
| ✅ **New** | `MessageExtraData.Attachment.originalSize: Long?` — on the message row, both directions. `null` = unknown. |
| ✅ **New (wire)** | The file start package carries the size: 3 bytes, big-endian, after the 50-byte name — the same layout as the JS client. |
| ↔ **Unchanged** | Every callback signature, `FileSummary`, `MessageState`, and the database schema. |

No database migration: the field lives inside the existing `extra_data` JSON and is absent
from rows written before this change.

**Binary compatibility:** both classes gained a trailing, defaulted constructor parameter.
Source that constructs or `copy()`s them still compiles; an app **compiled against the
previous SDK** must be rebuilt, not just re-linked.

---

## 2. What the number means

| File type | `originalSize` is |
|---|---|
| **File** | the file before gzip — what the user will get when it is opened |
| **Contact** | the contact CSV before gzip |
| **Image** | the image as sent (images are not compressed) |

It is **not** the airtime or the number of bytes on the radio — the gzipped payload, plus
parity, is what goes on air. Use `numOfPackages` / `estimatedTransferSeconds()` for that.

### When it is unknown

| Where | Unknown value | Why it can be unknown |
|---|---|---|
| `FileTransferData.Receive.originalSize` | `0` | the sender's SDK predates the field; the file is over 16 MiB (the field is 3 bytes) |
| `Attachment.originalSize` | `null` | the same, plus every row written before this change |

**Never show "0 B".** Unknown means omit the size, not zero.

---

## 3. Where to read it

### 3.1 While a file is arriving — callbacks

`FileTransferData.Receive` is passed to `receiveFileStatus`, `receiveFailure`, `receiveFile`
and `receiveImage`. `originalSize` is set from the first package and does not change for the
life of the transfer.

```kotlin
override fun receiveFileStatus(data: FileTransferData.Receive, percentage: Int) {
    val size = data.originalSize.takeIf { it > 0 }?.toLong()
    // bind to the bubble for data.messageId: percentage + size
}
```

### 3.2 On the message row — the conversation

```kotlin
val attachment = message.extraData as? MessageExtraData.Attachment ?: return
val size: Long? = attachment.originalSize ?: attachment.fileSummary?.size
```

Prefer `originalSize`, fall back to `fileSummary?.size`:

| Row state | `originalSize` | `fileSummary?.size` |
|---|---|---|
| `RECEIVING` | ✅ set (if the sender sent it) | `null` — no file yet |
| `RECEIVED` | ✅ set | ✅ the file on disk — equal to `originalSize` |
| `FAILED` (receive) | ✅ set | `null` — no file was written |
| `SENDING` / `SENT` / `FAILED` / `CANCELLED` (send) | ✅ set | ✅ the local copy |
| any row from before this change | `null` | whatever it was |

The fallback is what keeps old rows, and rows from older senders, showing a size where one
existed before.

---

## 4. UI to implement

1. **Incoming bubble, in progress** — show the size next to the progress ring as soon as the
   row appears (`RECEIVING`). Before this change there was nothing to show until the file
   had arrived.
2. **Incoming bubble, failed** — show the size of the file that did not arrive. It tells the
   operator whether asking for a resend is worth the airtime.
3. **Outgoing bubble** — unchanged in practice: the size was already available from
   `fileSummary`; reading `originalSize` first just makes both ends agree.
4. **Unknown size** — hide the size label. Do not show `0 B`, `?` or a placeholder.

### Formatting

Bytes in, your string out. Android's `Formatter.formatShortFileSize(context, bytes)` is
locale-aware and fine for this. Keep units consistent with the rest of the plugin.

---

## 5. Checklist

- [ ] Rebuild against the new SDK (binary change, §1).
- [ ] Read `originalSize ?: fileSummary?.size` wherever an attachment's size is shown.
- [ ] Show size on `RECEIVING` and receive-`FAILED` bubbles.
- [ ] Treat `0` (callbacks) and `null` (rows) as unknown and hide the label.
- [ ] Format in the app; localise.
- [ ] Test with a sender on the previous SDK — size must be hidden, not `0 B`.
- [ ] Test with the JS client — it already sends the size.
