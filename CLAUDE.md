# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Run

```bash
# Build debug APK
./gradlew assembleDebug

# Run unit tests (host JVM)
./gradlew test

# Run instrumentation tests (requires device/emulator)
./gradlew connectedAndroidTest

# Build from the Notesmaster subdirectory
cd Notesmaster && ./gradlew assembleDebug
```

Open the `Notesmaster/` directory in Android Studio for IDE support. The root project name is `Notes-master`.

## Architecture Overview

This is **Notesmaster** — an Android note-taking app forked from the MIUI MiCode Notes open-source project (Apache 2.0 licensed). Package: `net.micode.notes`. Three languages: English, Simplified Chinese (zh-rCN), Traditional Chinese (zh-rTW).

### Data Layer: Two-Table Design

The database (`note.db`, version 5) has two tables connected via SQLite triggers:

- **`note`** — Folders and notes metadata (parent_id, type, snippet, alert_date, bg_color_id, widget info, sync metadata). System folders use negative IDs: `0`=root, `-1`=temporary, `-2`=call_record, `-3`=trash, `-4`=sms_record.
- **`data`** — MIME-typed content rows linked to notes by `note_id`. Three MIME types: `text_note` (checklist mode, content), `call_note` (phone number, call date), `sms_note` (phone number, sms date, body). Generic columns `data1` through `data5` carry type-specific fields.

`NotesProvider` (authority: `micode_notes`) exposes `/note`, `/data`, `/search` URIs and increments `version` on every note update. SQL triggers handle auto-count updates, cascade deletes, and snippet sync from data to note.

### Model Layer: Note + WorkingNote

- **`Note.java`** — Tracks dirty ContentValues for note metadata and per-type data (text, call, sms). `syncNote()` pushes changes to ContentResolver with insert-or-update logic for each data type.
- **`WorkingNote.java`** — The editing wrapper. CRITICAL: `isWorthSaving()` requires non-empty `mContent` — auto-generated call/sms notes **must** set working text (e.g., the call date as a snippet) or `saveNote()` silently returns false. Factory methods: `createEmptyNote()` for new, `load()` for existing. `convertToCallNote()` / `convertToSmsNote()` set the MIME-type data and change parent folder.

### UI Layer

- **`NotesListActivity`** — Launcher activity. Manages note list with multi-select ActionMode, folder navigation (with 4 states: NOTE_LIST, SUB_FOLDER, CALL_RECORD_FOLDER, SMS_RECORD_FOLDER), context menus for folders, and export/sync options. On first launch, creates an introduction note from raw resources. On resume, scans CallLog and sent SMS for new records. Registers a `ContentObserver` on `content://sms` for real-time sent SMS detection.
- **`NoteEditActivity`** — Note editor. Supports text mode and checklist mode (items prefixed with ✓/☐ markers). Font size selector (4 sizes), background color picker (5 colors: yellow/blue/white/green/red). Alarm setting with `DateTimePickerDialog`. Share, desktop shortcut, delete. Implements the three-tier alarm permission flow (see "Alarm System" below).
- **`NoteItemData`** — Extracts display data from a Cursor row. For call/sms folder items, looks up the phone number from the data table and resolves contact name via `Contact.getContact()`.
- **`NotesListItem`** — Binds `NoteItemData` to list item views.

### Alarm System

The alarm flow crosses multiple components:

1. User taps "Remind me" in `NoteEditActivity` → `checkAlarmPermissions()` verifies three permissions:
   - `POST_NOTIFICATIONS` (API 33+) — standard runtime dialog
   - `SCHEDULE_EXACT_ALARM` (API 31+) — guided to system settings
   - `USE_FULL_SCREEN_INTENT` (API 34+) — guided to system settings
2. If all granted → `DateTimePickerDialog` shown → `setAlertDate()` registers with `AlarmManager.setExactAndAllowWhileIdle()`
3. At alarm time → `AlarmReceiver` fires a high-priority `Notification` with `setFullScreenIntent()` pointing to `AlarmAlertActivity`
4. `AlarmAlertActivity` shows a full-screen dialog (above lock screen) with alarm sound
5. After reboot → `AlarmInitReceiver` re-registers all non-expired alarms

### Auto-Creation: Call & SMS Notes

**Call notes** (dual mechanism, because `PHONE_STATE` broadcast is restricted in API 34+):
- `CallReceiver` — BroadcastReceiver for `PHONE_STATE`/`NEW_OUTGOING_CALL` (legacy path)
- `CallLogObserver` (in `NotesListActivity`) — ContentObserver on `CallLog.Calls`. On `onResume()`, scans for new call log entries since `last_call_log_check` (stored in SharedPreferences). Calls `CallReceiver.createCallNote()` (static).

**SMS notes** (dual mechanism):
- `SmsReceiver` — BroadcastReceiver for `SMS_RECEIVED` (incoming)
- `SmsSentObserver` (in `NotesListActivity`) — ContentObserver on `content://sms`. On `onResume()`, scans `content://sms/sent` for new entries since `last_sms_check`. Calls `SmsReceiver.createSmsNote()` (static).

Both use `WorkingNote.createEmptyNote()` → `convertToXxxNote()` → `setWorkingText()` → `saveNote()` with deduplication via `DataUtils.getNoteIdByPhoneNumberAndXxxDate()`.

### Widget System

`NoteWidgetProvider` (abstract) supports 2x1 and 4x1 home screen widgets. Concrete implementations: `NoteWidgetProvider_2x`, `NoteWidgetProvider_4x`. Clicking a widget opens the note in `NoteEditActivity`.

### Google Task Sync (`gtask/`)

`GTaskSyncService` runs as an Android Service, using `GTaskManager` + `GTaskClient` (Apache HttpClient) to sync notes with Google Tasks. The sync is gated by whether a Google account is configured in preferences.

### Tools

- **`DataUtils.java`** — Batch operations (delete, move to folder), call/sms dedup queries, folder count, visibility checks
- **`ResourceParser.java`** — Maps integer IDs to drawable/style resources for colors and font sizes. Random color support via preferences.
- **`BackupUtils.java`** — Exports all notes as text files to external storage

## Dependencies

- Gradle 9.0.0 AGP, Java 11, minSdk 24, targetSdk 36
- AndroidX: appcompat 1.6.1, material 1.10.0
- **External JARs** (absolute-path references in `app/build.gradle.kts`): Apache HttpClient 4.5.14 (`httpclient-osgi`, `httpclient-win`, `httpcore`) — these are on `D:\CODE\develop\`. These JARs are needed for Google Task sync. If they are missing, the project may still compile but sync functionality will fail at runtime.

## Key Patterns & Pitfalls

- **`WorkingNote.isWorthSaving()`** returns false if `mContent` is empty AND the note doesn't exist in the database. When auto-creating notes from broadcasts/observers, always call `setWorkingText()` before `saveNote()`, otherwise the save silently does nothing.
- **Phone number equality** uses SQLite's custom `PHONE_NUMBERS_EQUAL()` function (registered elsewhere) for fuzzy matching.
- **System folders** (negative IDs) cannot be deleted — `deleteFolder()` and `batchDelete()` protect them.
- **Checklist mode** uses unicode markers: TAG_CHECKED=`√` (✓), TAG_UNCHECKED=`□` (☐). Lines are split on `\n`.
- **Sync mode** is determined by whether a sync account name is configured in preferences. In sync mode, deletions are moves to trash folder (-3) instead of hard deletes.
- The app requests runtime permissions on first launch: `READ_PHONE_STATE`, `READ_CALL_LOG`, `READ_CONTACTS`, `READ_SMS`.
