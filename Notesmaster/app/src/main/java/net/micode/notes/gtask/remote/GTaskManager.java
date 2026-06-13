/*
 * Copyright (c) 2010-2011, The MiCode Open Source Community (www.micode.net)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.micode.notes.gtask.remote;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.util.Log;

import net.micode.notes.R;
import net.micode.notes.data.Notes;
import net.micode.notes.data.Notes.DataColumns;
import net.micode.notes.data.Notes.NoteColumns;
import net.micode.notes.gtask.data.SqlNote;
import net.micode.notes.gtask.data.Task;
import net.micode.notes.gtask.data.TaskList;
import net.micode.notes.sync.SyncClient;
import net.micode.notes.sync.SyncClientFactory;
import net.micode.notes.sync.SyncException;
import net.micode.notes.tool.DataUtils;
import net.micode.notes.tool.GTaskStringUtils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashMap;


public class GTaskManager {
    private static final String TAG = GTaskManager.class.getSimpleName();

    public static final int STATE_SUCCESS = 0;

    public static final int STATE_NETWORK_ERROR = 1;

    public static final int STATE_INTERNAL_ERROR = 2;

    public static final int STATE_SYNC_IN_PROGRESS = 3;

    public static final int STATE_SYNC_CANCELLED = 4;

    public static final int SYNC_MODE_BIDIRECTIONAL = 0;

    public static final int SYNC_MODE_UPLOAD_ONLY = 1;

    public static final int SYNC_MODE_DOWNLOAD_ONLY = 2;

    private static GTaskManager mInstance = null;

    private Activity mActivity;

    private Context mContext;

    private ContentResolver mContentResolver;

    private boolean mSyncing;

    private boolean mCancelled;

    private GTaskASyncTask asyncTask;

    private int mSyncMode;

    private int mSyncCountAdded;
    private int mSyncCountUpdated;
    private int mSyncCountDeleted;

    private GTaskManager() {
        mSyncing = false;
        mCancelled = false;
    }

    public static synchronized GTaskManager getInstance() {
        if (mInstance == null) {
            mInstance = new GTaskManager();
        }
        return mInstance;
    }

    public synchronized void setActivityContext(Activity activity) {
        mActivity = activity;
    }

    // ── Main entry point ────────────────────────────────────────────

    public int sync(Context context, GTaskASyncTask asyncTask, int syncMode) {
        if (mSyncing) {
            Log.d(TAG, "Sync is in progress");
            return STATE_SYNC_IN_PROGRESS;
        }
        mContext = context;
        mContentResolver = mContext.getContentResolver();
        this.asyncTask = asyncTask;
        mSyncMode = syncMode;
        mSyncing = true;
        mCancelled = false;
        mSyncCountAdded = 0;
        mSyncCountUpdated = 0;
        mSyncCountDeleted = 0;

        try {
            SyncClient syncClient = SyncClientFactory.getSyncClient(mContext);
            if (syncClient == null) {
                Log.e(TAG, "no sync client configured");
                asyncTask.publishProgess("没有配置同步服务，请先在设置中配置 GitHub Token");
                return STATE_INTERNAL_ERROR;
            }
            syncClient.resetUpdateState();

            // Login
            if (!mCancelled) {
                Log.d(TAG, "sync: logging in");
                if (!syncClient.login(mContext)) {
                    throw new SyncException(SyncException.AUTH_ERROR,
                            "login sync service failed");
                }
            }

            // Execute the sync
            if (mSyncMode == SYNC_MODE_UPLOAD_ONLY) {
                if (!mCancelled) uploadAll(syncClient);
            } else if (mSyncMode == SYNC_MODE_DOWNLOAD_ONLY) {
                if (!mCancelled) downloadAll(syncClient);
            } else {
                // Bidirectional: default to upload for safety
                if (!mCancelled) uploadAll(syncClient);
            }

            // Build summary
            StringBuilder summary = new StringBuilder();
            if (mSyncCountAdded > 0) {
                summary.append("新增 ").append(mSyncCountAdded).append(" 条");
            }
            if (mSyncCountUpdated > 0) {
                if (summary.length() > 0) summary.append("，");
                summary.append("更新 ").append(mSyncCountUpdated).append(" 条");
            }
            if (summary.length() == 0) {
                if (mSyncMode == SYNC_MODE_DOWNLOAD_ONLY) {
                    summary.append("远程仓库无新文件可下载");
                } else {
                    summary.append("无新笔记需上传");
                }
            }
            Log.d(TAG, "sync: " + summary.toString());
            asyncTask.publishProgess(summary.toString());

        } catch (SyncException e) {
            Log.e(TAG, "SyncException: " + e.toString(), e);
            asyncTask.publishProgess("同步失败: " + e.getMessage());
            if (e.getErrorCode() == SyncException.NETWORK_ERROR
                    || e.getErrorCode() == SyncException.AUTH_ERROR) {
                return STATE_NETWORK_ERROR;
            }
            return STATE_INTERNAL_ERROR;
        } catch (Exception e) {
            Log.e(TAG, "Unexpected sync error: " + e.toString(), e);
            String detail = e.getClass().getSimpleName() + ": "
                    + (e.getMessage() != null ? e.getMessage() : "(no message)");
            asyncTask.publishProgess("同步异常: " + detail);
            return STATE_INTERNAL_ERROR;
        } finally {
            mSyncing = false;
        }

        return mCancelled ? STATE_SYNC_CANCELLED : STATE_SUCCESS;
    }

    // ── Upload — overwrite remote with all local notes ──────────────

    private void uploadAll(SyncClient syncClient) throws SyncException {
        Log.d(TAG, "uploadAll: starting upload-overwrite");

        // Step 1: Clear remote and reset local sync metadata
        asyncTask.publishProgess("清空远程仓库...");
        clearAllRemote(syncClient);
        resetLocalSyncMetadata();

        // Step 2: Create Default container
        String defaultPath = GTaskStringUtils.MIUI_FOLDER_PREFFIX
                + GTaskStringUtils.FOLDER_DEFAULT;
        ensureUploadContainer(syncClient, defaultPath);
        HashMap<Long, String> folderIdToPath = new HashMap<>();
        folderIdToPath.put((long) Notes.ID_ROOT_FOLDER, defaultPath);

        // Step 3: Upload custom folders, building ID → path map
        asyncTask.publishProgess("上传文件夹...");
        Cursor folderCursor = null;
        try {
            folderCursor = mContentResolver.query(Notes.CONTENT_NOTE_URI,
                    SqlNote.PROJECTION_NOTE,
                    "(type=? AND parent_id<>? AND _id<>? AND _id<>?)",
                    new String[] {
                            String.valueOf(Notes.TYPE_FOLDER),
                            String.valueOf(Notes.ID_TRASH_FOLER),
                            String.valueOf(Notes.ID_CALL_RECORD_FOLDER),
                            String.valueOf(Notes.ID_SMS_RECORD_FOLDER)
                    }, null);
            if (folderCursor != null) {
                while (folderCursor.moveToNext()) {
                    if (mCancelled) return;
                    long folderId = folderCursor.getLong(SqlNote.ID_COLUMN);
                    String snippet = folderCursor.getString(SqlNote.SNIPPET_COLUMN);
                    if (snippet == null || snippet.trim().isEmpty()) continue;
                    String path = GTaskStringUtils.MIUI_FOLDER_PREFFIX + snippet;
                    ensureUploadContainer(syncClient, path);
                    folderIdToPath.put(folderId, path);

                    // Set gid on the local folder
                    SqlNote folderNote = new SqlNote(mContext, folderCursor);
                    folderNote.setGtaskId(path);
                    folderNote.commit(false);
                    folderNote.resetLocalModified();
                    folderNote.commit(true);
                }
            }
        } finally {
            if (folderCursor != null) folderCursor.close();
        }

        // Step 4: Upload all notes
        asyncTask.publishProgess("上传笔记...");
        Cursor noteCursor = null;
        try {
            noteCursor = mContentResolver.query(Notes.CONTENT_NOTE_URI,
                    SqlNote.PROJECTION_NOTE,
                    "(type=? AND parent_id<>? AND parent_id<>? AND parent_id<>? AND parent_id<>?)",
                    new String[] {
                            String.valueOf(Notes.TYPE_NOTE),
                            String.valueOf(Notes.ID_TRASH_FOLER),
                            String.valueOf(Notes.ID_TEMPARAY_FOLDER),
                            String.valueOf(Notes.ID_CALL_RECORD_FOLDER),
                            String.valueOf(Notes.ID_SMS_RECORD_FOLDER)
                    }, NoteColumns.TYPE + " DESC");
            if (noteCursor != null) {
                while (noteCursor.moveToNext()) {
                    if (mCancelled) return;
                    SqlNote sqlNote = new SqlNote(mContext, noteCursor);
                    JSONObject content = sqlNote.getContent();
                    if (content == null) {
                        Log.w(TAG, "uploadAll: skipping note with null content, id="
                                + sqlNote.getId());
                        continue;
                    }

                    long parentId = sqlNote.getParentId();
                    String containerPath = folderIdToPath.get(parentId);
                    if (containerPath == null) {
                        // Note in an unknown folder (e.g. root) — put in Default
                        containerPath = defaultPath;
                    }

                    // Build Task from local content and upload
                    Task task = new Task();
                    task.setNotes(content.toString());
                    task.setContentByLocalJSON(content);
                    String gid = syncClient.createItem(containerPath, task);

                    if (gid != null && !gid.isEmpty()) {
                        sqlNote.setGtaskId(gid);
                        sqlNote.resetLocalModified();
                        sqlNote.commit(true);
                        mSyncCountAdded++;
                    }
                }
            }
        } finally {
            if (noteCursor != null) noteCursor.close();
        }

        Log.d(TAG, "uploadAll: finished, uploaded " + mSyncCountAdded + " notes");
    }

    // ── Download — incrementally pull remote notes to local ─────────

    private void downloadAll(SyncClient syncClient) throws SyncException {
        Log.d(TAG, "downloadAll: starting incremental download");

        asyncTask.publishProgess("列出远程目录...");
        JSONArray containers = syncClient.listContainers();
        Log.d(TAG, "downloadAll: got " + containers.length() + " containers");

        String defaultPath = GTaskStringUtils.MIUI_FOLDER_PREFFIX
                + GTaskStringUtils.FOLDER_DEFAULT;
        String metaName = GTaskStringUtils.MIUI_FOLDER_PREFFIX
                + GTaskStringUtils.FOLDER_META;
        HashMap<String, Long> pathToFolderId = new HashMap<>();
        pathToFolderId.put(defaultPath, (long) Notes.ID_ROOT_FOLDER);

        for (int ci = 0; ci < containers.length(); ci++) {
            if (mCancelled) return;
            try {
                JSONObject container = containers.getJSONObject(ci);
                String containerPath = container.getString(GTaskStringUtils.GTASK_JSON_ID);
                String containerName = container.getString(GTaskStringUtils.GTASK_JSON_NAME);

                // Skip METADATA container (legacy from old sync)
                if (metaName.equals(containerName)) {
                    Log.d(TAG, "downloadAll: skipping METADATA container");
                    continue;
                }

                asyncTask.publishProgess("下载 " + containerName + "...");

                // Ensure local folder exists
                long folderId = ensureLocalFolder(containerPath, containerName);
                pathToFolderId.put(containerPath, folderId);

                // List and download all note files
                JSONArray items = syncClient.listItems(containerPath);
                for (int ii = 0; ii < items.length(); ii++) {
                    if (mCancelled) return;
                    JSONObject item = items.getJSONObject(ii);
                    String itemPath = item.getString(GTaskStringUtils.GTASK_JSON_ID);

                    Task task = new Task();
                    task.setContentByRemoteJSON(item);

                    if (!task.isWorthSaving()) continue;

                    JSONObject noteJson = task.getLocalJSONFromContent();
                    if (noteJson == null) continue;

                    // Check if we already have this note locally
                    long existingId = findLocalNoteByGid(itemPath);
                    SqlNote sqlNote;
                    if (existingId > 0) {
                        // Update existing note — keep JSON as-is
                        sqlNote = new SqlNote(mContext, existingId);
                        sqlNote.setContent(noteJson);
                        sqlNote.setParentId(folderId);
                        sqlNote.setGtaskId(itemPath);
                        sqlNote.commit(true);
                        mSyncCountUpdated++;
                    } else {
                        // New note — strip stale IDs from source device first
                        stripStaleIds(noteJson);
                        sqlNote = new SqlNote(mContext);
                        sqlNote.setContent(noteJson);
                        sqlNote.setParentId(folderId);
                        sqlNote.setGtaskId(itemPath);
                        sqlNote.commit(false);
                        mSyncCountAdded++;
                    }

                    sqlNote.resetLocalModified();
                    sqlNote.commit(true);
                }
            } catch (JSONException e) {
                Log.e(TAG, "downloadAll: JSON error", e);
                throw new SyncException(SyncException.DATA_ERROR,
                        "Failed to process container during download", e);
            }
        }

        Log.d(TAG, "downloadAll: finished, added=" + mSyncCountAdded
                + " updated=" + mSyncCountUpdated);
    }

    // ── Helpers ─────────────────────────────────────────────────────

    /**
     * Ensure a container exists on remote (idempotent — skips if already present).
     */
    private void ensureUploadContainer(SyncClient syncClient, String containerName)
            throws SyncException {
        JSONArray existing = syncClient.listContainers();
        for (int i = 0; i < existing.length(); i++) {
            try {
                JSONObject c = existing.getJSONObject(i);
                if (containerName.equals(c.optString(GTaskStringUtils.GTASK_JSON_NAME))) {
                    return; // Already exists
                }
            } catch (JSONException e) {
                Log.w(TAG, "ensureUploadContainer: JSON error checking existing", e);
            }
        }
        TaskList taskList = new TaskList();
        taskList.setName(containerName);
        syncClient.createContainer(taskList);
        Log.d(TAG, "ensureUploadContainer: created " + containerName);
    }

    /**
     * Ensure a folder exists locally for the given remote path/name.
     * Returns the local folder ID (creates if not found).
     */
    private long ensureLocalFolder(String remotePath, String remoteName) throws SyncException {
        String folderName = remoteName;
        if (folderName.startsWith(GTaskStringUtils.MIUI_FOLDER_PREFFIX)) {
            folderName = folderName.substring(GTaskStringUtils.MIUI_FOLDER_PREFFIX.length());
        }

        if (folderName.equals(GTaskStringUtils.FOLDER_DEFAULT)) {
            return Notes.ID_ROOT_FOLDER;
        }

        // Check if folder exists locally by snippet (ignoring trashed folders)
        Cursor c = null;
        try {
            c = mContentResolver.query(Notes.CONTENT_NOTE_URI,
                    new String[] { NoteColumns.ID },
                    NoteColumns.TYPE + "=? AND " + NoteColumns.SNIPPET + "=? AND "
                            + NoteColumns.PARENT_ID + "<>?",
                    new String[] {
                            String.valueOf(Notes.TYPE_FOLDER), folderName,
                            String.valueOf(Notes.ID_TRASH_FOLER)
                    }, null);
            if (c != null && c.moveToFirst()) {
                long id = c.getLong(0);
                // Update gid on existing folder
                SqlNote fn = new SqlNote(mContext, id);
                fn.setGtaskId(remotePath);
                fn.commit(false);
                fn.resetLocalModified();
                fn.commit(true);
                return id;
            }
        } finally {
            if (c != null) c.close();
        }

        // Create new folder locally
        SqlNote folderNote = new SqlNote(mContext);
        TaskList taskList = new TaskList();
        taskList.setName(remoteName);
        JSONObject folderJson = taskList.getLocalJSONFromContent();
        folderNote.setContent(folderJson);
        folderNote.setParentId(Notes.ID_ROOT_FOLDER);
        folderNote.setGtaskId(remotePath);
        folderNote.commit(false);
        folderNote.resetLocalModified();
        folderNote.commit(true);

        Log.d(TAG, "ensureLocalFolder: created '" + folderName + "' id=" + folderNote.getId());
        return folderNote.getId();
    }

    /**
     * Find a local note by its remote gtask_id.
     * Returns the note ID, or -1 if not found.
     */
    private long findLocalNoteByGid(String gid) {
        Cursor c = null;
        try {
            c = mContentResolver.query(Notes.CONTENT_NOTE_URI,
                    new String[] { NoteColumns.ID },
                    NoteColumns.GTASK_ID + "=? AND " + NoteColumns.GTASK_ID + "!=''",
                    new String[] { gid }, null);
            if (c != null && c.moveToFirst()) {
                return c.getLong(0);
            }
        } finally {
            if (c != null) c.close();
        }
        return -1;
    }

    /**
     * Strip note/data IDs from a downloaded JSON that belong to another device.
     */
    private void stripStaleIds(JSONObject noteJson) {
        try {
            if (noteJson.has(GTaskStringUtils.META_HEAD_NOTE)) {
                JSONObject note = noteJson.getJSONObject(GTaskStringUtils.META_HEAD_NOTE);
                if (note.has(NoteColumns.ID)) {
                    long id = note.getLong(NoteColumns.ID);
                    if (DataUtils.existInNoteDatabase(mContentResolver, id)) {
                        note.remove(NoteColumns.ID);
                    }
                }
            }
            if (noteJson.has(GTaskStringUtils.META_HEAD_DATA)) {
                JSONArray dataArray = noteJson.getJSONArray(GTaskStringUtils.META_HEAD_DATA);
                for (int i = 0; i < dataArray.length(); i++) {
                    JSONObject data = dataArray.getJSONObject(i);
                    if (data.has(DataColumns.ID)) {
                        long dataId = data.getLong(DataColumns.ID);
                        if (DataUtils.existInDataDatabase(mContentResolver, dataId)) {
                            data.remove(DataColumns.ID);
                        }
                    }
                }
            }
        } catch (JSONException e) {
            Log.w(TAG, "stripStaleIds: JSON error", e);
        }
    }

    // ── Remote cleanup ──────────────────────────────────────────────

    /**
     * Delete all remote containers and their files.
     */
    private void clearAllRemote(SyncClient syncClient) throws SyncException {
        try {
            JSONArray containers = syncClient.listContainers();
            for (int i = 0; i < containers.length(); i++) {
                JSONObject container = containers.getJSONObject(i);
                String containerId = container.getString(GTaskStringUtils.GTASK_JSON_ID);
                Log.d(TAG, "clearAllRemote: deleting items in " + containerId);
                try {
                    JSONArray items = syncClient.listItems(containerId);
                    for (int j = 0; j < items.length(); j++) {
                        JSONObject item = items.getJSONObject(j);
                        String itemId = item.getString(GTaskStringUtils.GTASK_JSON_ID);
                        try {
                            syncClient.deleteItem(itemId);
                        } catch (Exception e) {
                            Log.w(TAG, "clearAllRemote: failed to delete " + itemId
                                    + ": " + e.getMessage());
                        }
                    }
                } catch (Exception e) {
                    Log.w(TAG, "clearAllRemote: failed to list " + containerId
                            + ": " + e.getMessage());
                }
                try {
                    syncClient.deleteItem(containerId + "/.gitkeep");
                } catch (Exception e) {
                    Log.w(TAG, "clearAllRemote: failed to delete .gitkeep for " + containerId);
                }
            }
            Log.d(TAG, "clearAllRemote: done");
        } catch (JSONException e) {
            throw new SyncException(SyncException.DATA_ERROR,
                    "Failed to clear remote containers", e);
        }
    }

    /**
     * Reset gtask_id and sync_id for all non-system notes.
     */
    private void resetLocalSyncMetadata() {
        ContentValues values = new ContentValues();
        values.put(NoteColumns.GTASK_ID, "");
        values.put(NoteColumns.SYNC_ID, 0);
        int updated = mContentResolver.update(Notes.CONTENT_NOTE_URI, values,
                "type<>?", new String[] { String.valueOf(Notes.TYPE_SYSTEM) });
        Log.d(TAG, "resetLocalSyncMetadata: cleared gid/sync_id for " + updated + " items");
    }

    // ── Public utilities ────────────────────────────────────────────

    public String getSyncAccount() {
        if (mContext == null) {
            return "";
        }
        SyncClient syncClient = SyncClientFactory.getSyncClient(mContext);
        if (syncClient != null) {
            return syncClient.getAccountIdentifier();
        }
        return "";
    }

    public void cancelSync() {
        mCancelled = true;
    }
}
