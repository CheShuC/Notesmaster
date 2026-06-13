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

package net.micode.notes.sync.github;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;
import android.util.Base64;
import android.util.Log;

import net.micode.notes.gtask.data.Node;
import net.micode.notes.gtask.data.Task;
import net.micode.notes.gtask.data.TaskList;
import net.micode.notes.sync.SyncClient;
import net.micode.notes.sync.SyncException;
import net.micode.notes.tool.GTaskStringUtils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * GitHub-based implementation of {@link SyncClient}.
 * <p>
 * Uses the GitHub Contents API to store notes as JSON files in a private
 * repository. Each folder is a subdirectory, each note is a {@code .json} file
 * containing the output of {@code Node.getLocalJSONFromContent()}.
 * <p>
 * Authentication: Personal Access Token (PAT) with {@code repo} scope,
 * stored in SharedPreferences.
 * <p>
 * Repository: {@code xiaomibianqian} (private, created by the user beforehand).
 */
public class GitHubSyncClient implements SyncClient {

    private static final String TAG = "GitHubSyncClient";

    private static final String GITHUB_API = "https://api.github.com";

    private static final String PREF_NAME = "notes_preferences";

    private static final String KEY_AUTH_TOKEN = "pref_github_token";

    private static final String KEY_REPO = "pref_github_repo";

    private static final String KEY_OWNER = "pref_github_owner";

    private static final String DEFAULT_REPO = "xiaomibianqian";

    private static final MediaType JSON_MEDIA_TYPE = MediaType.parse("application/json; charset=utf-8");

    private static final String GITKEEP = ".gitkeep";

    private static GitHubSyncClient sInstance;

    private Context mContext;

    private OkHttpClient mHttpClient;

    private String mOwner;

    private String mRepo;

    private String mToken;

    private boolean mLoggedIn;

    /** Cached file SHA values: path → sha */
    private HashMap<String, String> mShaCache;

    private GitHubSyncClient(Context context) {
        mContext = context.getApplicationContext();
        mHttpClient = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .addInterceptor(new okhttp3.Interceptor() {
                    @Override
                    public okhttp3.Response intercept(Chain chain) throws IOException {
                        okhttp3.Request request = chain.request();
                        int maxRetries = 3;
                        int retryCount = 0;
                        IOException lastException = null;

                        while (retryCount <= maxRetries) {
                            try {
                                okhttp3.Response response = chain.proceed(request);
                                if (response.isSuccessful() || response.code() < 500) {
                                    return response;
                                }
                                // Server error (5xx) — retry
                                response.close();
                                Log.w(TAG, "HTTP " + response.code() + " for " + request.url().encodedPath()
                                        + " (attempt " + (retryCount + 1) + "/" + (maxRetries + 1) + ")");
                            } catch (IOException e) {
                                lastException = e;
                                Log.w(TAG, "Network error for " + request.url().encodedPath()
                                        + ": " + e.getMessage()
                                        + " (attempt " + (retryCount + 1) + "/" + (maxRetries + 1) + ")");
                            }

                            if (retryCount < maxRetries) {
                                try {
                                    Thread.sleep((long) Math.pow(2, retryCount) * 1000);
                                } catch (InterruptedException ignored) {
                                    Thread.currentThread().interrupt();
                                    break;
                                }
                            }
                            retryCount++;
                        }

                        if (lastException != null) throw lastException;
                        throw new IOException("Request failed after " + (maxRetries + 1) + " attempts: " + request.url().encodedPath());
                    }
                })
                .build();
        mShaCache = new HashMap<>();
        mLoggedIn = false;
    }

    public static synchronized GitHubSyncClient getInstance(Context context) {
        if (sInstance == null) {
            sInstance = new GitHubSyncClient(context);
        }
        return sInstance;
    }

    /**
     * Reset the cached singleton so the next {@link #getInstance} creates a new one.
     * Called by {@link net.micode.notes.sync.SyncClientFactory#setSyncProvider} when the
     * user switches providers.
     */
    public static synchronized void resetInstance() {
        sInstance = null;
    }

    // ── Configuration helpers (static, for use by UI/settings) ────

    public static String getAuthToken(Context context) {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .getString(KEY_AUTH_TOKEN, "");
    }

    public static void setAuthToken(Context context, String token) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit().putString(KEY_AUTH_TOKEN, token).commit();
    }

    public static String getRepository(Context context) {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .getString(KEY_REPO, DEFAULT_REPO);
    }

    public static void setRepository(Context context, String repo) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit().putString(KEY_REPO, repo).commit();
    }

    public static String getOwner(Context context) {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .getString(KEY_OWNER, "");
    }

    public static void setOwner(Context context, String owner) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit().putString(KEY_OWNER, owner).commit();
    }

    // ── SyncClient implementation ──────────────────────────────────

    @Override
    public boolean login(Context context) throws SyncException {
        mToken = getAuthToken(context);
        if (TextUtils.isEmpty(mToken)) {
            Log.e(TAG, "No GitHub token configured");
            return false;
        }

        mRepo = getRepository(context);

        // Determine owner: use cached value or query /user endpoint
        mOwner = getOwner(context);
        if (TextUtils.isEmpty(mOwner)) {
            try {
                mOwner = queryUsername();
                setOwner(context, mOwner);
            } catch (SyncException e) {
                Log.e(TAG, "Failed to get GitHub username: " + e.getMessage());
                return false;
            }
        }

        // Verify the token works by checking the repository
        try {
            verifyRepository();
        } catch (SyncException e) {
            Log.e(TAG, "Repository verification failed: " + e.getMessage());
            mLoggedIn = false;
            return false;
        }

        mShaCache.clear();
        mLoggedIn = true;
        Log.d(TAG, "Logged in as " + mOwner + ", repo: " + mRepo);
        return true;
    }

    @Override
    public String getAccountIdentifier() {
        if (!TextUtils.isEmpty(mOwner)) {
            return mOwner;
        }
        return getOwner(mContext);
    }

    @Override
    public JSONArray listContainers() throws SyncException {
        ensureLoggedIn();
        try {
            String response;
            try {
                response = apiGet("/repos/" + mOwner + "/" + mRepo + "/contents");
            } catch (SyncException e) {
                // Empty repo (no commits yet) returns 404 — create default structure
                if (e.getErrorCode() == SyncException.DATA_ERROR
                        && e.getMessage() != null
                        && e.getMessage().contains("not found")) {
                    Log.d(TAG, "Repo is empty, creating default containers");
                    ensureDefaultContainers();
                    response = apiGet("/repos/" + mOwner + "/" + mRepo + "/contents");
                } else {
                    throw e;
                }
            }

            JSONArray rootItems = new JSONArray(response);
            JSONArray containers = new JSONArray();

            for (int i = 0; i < rootItems.length(); i++) {
                JSONObject item = rootItems.getJSONObject(i);
                if ("dir".equals(item.optString("type"))) {
                    String name = item.getString("name");
                    String path = item.getString("path");
                    containers.put(buildContainerJson(path, name, item));
                }
            }

            // If no containers found, create the default ones
            if (containers.length() == 0) {
                ensureDefaultContainers();
                // Re-list — must succeed now
                response = apiGet("/repos/" + mOwner + "/" + mRepo + "/contents");
                rootItems = new JSONArray(response);
                for (int i = 0; i < rootItems.length(); i++) {
                    JSONObject item = rootItems.getJSONObject(i);
                    if ("dir".equals(item.optString("type"))) {
                        containers.put(buildContainerJson(
                                item.getString("path"), item.getString("name"), item));
                    }
                }
                if (containers.length() == 0) {
                    throw new SyncException(SyncException.DATA_ERROR,
                            "Repository has no containers after initialization. "
                            + "Check that the GitHub token has repo scope and the "
                            + "repository " + mOwner + "/" + mRepo + " exists.");
                }
            }

            Log.d(TAG, "listContainers: found " + containers.length() + " containers");
            return containers;
        } catch (JSONException e) {
            throw new SyncException(SyncException.DATA_ERROR,
                    "Failed to parse container listing", e);
        }
    }

    @Override
    public JSONArray listItems(String containerId) throws SyncException {
        ensureLoggedIn();
        try {
            String response = apiGet("/repos/" + mOwner + "/" + mRepo
                    + "/contents/" + urlEncode(containerId));
            JSONArray files = new JSONArray(response);
            JSONArray items = new JSONArray();

            for (int i = 0; i < files.length(); i++) {
                JSONObject file = files.getJSONObject(i);
                String name = file.getString("name");

                // Skip .gitkeep placeholder files
                if (GITKEEP.equals(name)) {
                    continue;
                }

                if ("file".equals(file.optString("type")) && name.endsWith(".json")) {
                    // Fetch the actual file content
                    String fileResponse = apiGet("/repos/" + mOwner + "/" + mRepo
                            + "/contents/" + urlEncode(file.getString("path")));
                    JSONObject fileObj = new JSONObject(fileResponse);

                    String content = decodeContent(fileObj);
                    String sha = fileObj.optString("sha", "");
                    String path = fileObj.optString("path", "");

                    // Build a JSON object compatible with Node.setContentByRemoteJSON()
                    JSONObject item = new JSONObject();
                    item.put(GTaskStringUtils.GTASK_JSON_ID, path);
                    item.put(GTaskStringUtils.GTASK_JSON_NAME, name.substring(0, name.length() - 5)); // strip .json
                    item.put(GTaskStringUtils.GTASK_JSON_NOTES, content); // full file content as notes
                    item.put(GTaskStringUtils.GTASK_JSON_LAST_MODIFIED, 0L);
                    item.put(GTaskStringUtils.GTASK_JSON_DELETED, false);

                    // Cache sha for later updates/deletes
                    mShaCache.put(path, sha);

                    items.put(item);
                }
            }

            Log.d(TAG, "listItems: found " + items.length() + " items in " + containerId);
            return items;
        } catch (JSONException e) {
            throw new SyncException(SyncException.DATA_ERROR,
                    "Failed to parse item listing for " + containerId, e);
        }
    }

    @Override
    public String createContainer(Node container) throws SyncException {
        ensureLoggedIn();

        String folderName = container.getName();
        if (TextUtils.isEmpty(folderName)) {
            throw new SyncException(SyncException.DATA_ERROR,
                    "Container name is empty");
        }

        try {
            // Create a .gitkeep file inside the directory to make it exist in git
            String path = folderName + "/" + GITKEEP;
            String content = ""; // empty .gitkeep
            JSONObject result = putFile(path, content,
                    "Sync: create folder " + folderName, null);

            String gid = folderName;
            container.setGid(gid);

            Log.d(TAG, "Created container: " + gid);
            return gid;
        } catch (JSONException e) {
            throw new SyncException(SyncException.DATA_ERROR,
                    "Failed to create container " + folderName, e);
        }
    }

    @Override
    public String createItem(String containerId, Node item) throws SyncException {
        ensureLoggedIn();

        try {
            JSONObject localJson = item.getLocalJSONFromContent();
            if (localJson == null) {
                Log.w(TAG, "createItem: empty content for item name="
                        + item.getName() + " gid=" + item.getGid()
                        + " — skipping (note may be empty)");
                return item.getGid();
            }
            String jsonStr = localJson.toString();
            String gid = item.getGid();

            // Generate filename from note content (snippet), not random
            String filename;
            if (!TextUtils.isEmpty(gid)) {
                // Existing item: gid is the full path, extract filename
                String baseName = gid;
                int lastSlash = baseName.lastIndexOf('/');
                if (lastSlash >= 0) {
                    baseName = baseName.substring(lastSlash + 1);
                }
                if (baseName.endsWith(".json")) {
                    baseName = baseName.substring(0, baseName.length() - 5);
                }
                filename = baseName + ".json";
            } else {
                // New note: use the first line of note content as filename
                String noteName = item.getName();
                if (!TextUtils.isEmpty(noteName)) {
                    // Take only text before the first newline
                    String firstLine = noteName.trim();
                    int newlineIdx = firstLine.indexOf('\n');
                    if (newlineIdx > 0) {
                        firstLine = firstLine.substring(0, newlineIdx).trim();
                    }
                    String sanitized = sanitizeFilename(firstLine);
                    if (sanitized.length() > 0) {
                        filename = sanitized + ".json";
                    } else {
                        filename = "note_" + System.currentTimeMillis() + ".json";
                    }
                } else {
                    filename = "note_" + System.currentTimeMillis() + ".json";
                }
            }

            String path = containerId + "/" + filename;
            Log.d(TAG, "createItem: path=" + path + " containerId=" + containerId
                    + " filename=" + filename);
            JSONObject result = putFile(path, jsonStr,
                    "Sync: create " + filename, null);

            String newGid = result.optString("path", path);
            item.setGid(newGid);
            mShaCache.put(path, result.optString("sha", ""));

            Log.d(TAG, "Created item: " + newGid + " in " + containerId);
            return newGid;
        } catch (JSONException e) {
            throw new SyncException(SyncException.DATA_ERROR,
                    "Failed to create item in " + containerId, e);
        }
    }

    @Override
    public void updateItem(String gid, Node item) throws SyncException {
        ensureLoggedIn();

        try {
            JSONObject localJson = item.getLocalJSONFromContent();
            if (localJson == null) {
                Log.w(TAG, "Skipping update of item with empty content: " + gid);
                return;
            }
            String jsonStr = localJson.toString();
            String path = gid;
            String sha = mShaCache.get(path);

            JSONObject result = putFile(path, jsonStr,
                    "Sync: update " + path, sha);
            mShaCache.put(path, result.optString("sha", ""));

            Log.d(TAG, "Updated item: " + gid);
        } catch (JSONException e) {
            throw new SyncException(SyncException.DATA_ERROR,
                    "Failed to update item " + gid, e);
        }
    }

    @Override
    public void deleteItem(String gid) throws SyncException {
        ensureLoggedIn();

        String path = gid;
        String sha = mShaCache.get(path);

        if (TextUtils.isEmpty(sha)) {
            // Try to get the sha first
            try {
                String response = apiGet("/repos/" + mOwner + "/" + mRepo
                        + "/contents/" + urlEncode(path));
                JSONObject fileObj = new JSONObject(response);
                sha = fileObj.optString("sha", "");
            } catch (JSONException e) {
                // File might not exist, ignore
                Log.w(TAG, "Could not get sha for delete, item may not exist: " + gid);
                return;
            }
        }

        try {
            JSONObject body = new JSONObject();
            body.put("message", "Sync: delete " + path);
            body.put("sha", sha);
            body.put("branch", getDefaultBranch());

            apiDelete("/repos/" + mOwner + "/" + mRepo
                    + "/contents/" + urlEncode(path), body.toString());

            mShaCache.remove(path);
            Log.d(TAG, "Deleted item: " + gid);
        } catch (JSONException e) {
            throw new SyncException(SyncException.DATA_ERROR,
                    "Failed to delete item " + gid, e);
        }
    }

    @Override
    public void moveItem(String gid, String fromContainerId, String toContainerId)
            throws SyncException {
        ensureLoggedIn();

        // GitHub doesn't support move natively. Strategy:
        // 1. GET the source file content
        // 2. PUT to destination
        // 3. DELETE source
        try {
            // Step 1: Get source
            String srcResponse = apiGet("/repos/" + mOwner + "/" + mRepo
                    + "/contents/" + urlEncode(gid));
            JSONObject srcObj = new JSONObject(srcResponse);
            String content = decodeContent(srcObj);

            // Step 2: Create at destination
            String filename = gid;
            int lastSlash = filename.lastIndexOf('/');
            if (lastSlash >= 0) {
                filename = filename.substring(lastSlash + 1);
            }
            String destPath = toContainerId + "/" + filename;
            putFile(destPath, content,
                    "Sync: move " + gid + " → " + destPath, null);

            // Step 3: Delete source
            String srcSha = srcObj.optString("sha", "");
            JSONObject delBody = new JSONObject();
            delBody.put("message", "Sync: move " + gid + " → " + destPath);
            delBody.put("sha", srcSha);
            delBody.put("branch", getDefaultBranch());
            apiDelete("/repos/" + mOwner + "/" + mRepo
                    + "/contents/" + urlEncode(gid), delBody.toString());

            // Update cache
            mShaCache.remove(gid);
            mShaCache.put(destPath, ""); // sha will be refreshed on next list

            Log.d(TAG, "Moved " + gid + " → " + destPath);
        } catch (JSONException e) {
            throw new SyncException(SyncException.DATA_ERROR,
                    "Failed to move " + gid, e);
        }
    }

    @Override
    public void commitUpdates() throws SyncException {
        // GitHub API is atomic per-request; no batching needed
        // no-op
    }

    @Override
    public void resetUpdateState() {
        mShaCache.clear();
    }

    // ── GitHub API helpers ─────────────────────────────────────────

    private void ensureLoggedIn() throws SyncException {
        if (!mLoggedIn) {
            throw new SyncException(SyncException.AUTH_ERROR,
                    "Not logged in. Call login() first.");
        }
    }

    /**
     * Query the GitHub /user endpoint to get the authenticated username.
     */
    private String queryUsername() throws SyncException {
        try {
            String response = apiGet("/user");
            JSONObject userObj = new JSONObject(response);
            return userObj.getString("login");
        } catch (JSONException e) {
            throw new SyncException(SyncException.AUTH_ERROR,
                    "Failed to get user info from token", e);
        }
    }

    /**
     * Verify the configured repository exists and is accessible.
     */
    private void verifyRepository() throws SyncException {
        apiGet("/repos/" + mOwner + "/" + mRepo);
        Log.d(TAG, "Repository verified: " + mOwner + "/" + mRepo);
    }

    /**
     * Create default container directories if they don't exist.
     */
    private void ensureDefaultContainers() throws SyncException {
        // Only Default container — METADATA removed (notes are self-contained)
        String[] defaults = {
                GTaskStringUtils.MIUI_FOLDER_PREFFIX + GTaskStringUtils.FOLDER_DEFAULT
        };

        int created = 0;
        StringBuilder errors = new StringBuilder();
        for (String folderName : defaults) {
            try {
                String path = folderName + "/" + GITKEEP;
                putFile(path, "", "Sync: initialize " + folderName, null);
                created++;
                Log.d(TAG, "Created default container: " + folderName);
            } catch (Exception e) {
                // Collect all errors and report them
                String msg = folderName + ": " + e.getMessage();
                Log.e(TAG, "Failed to create container " + msg);
                errors.append(msg).append("; ");
            }
        }

        if (errors.length() > 0) {
            throw new SyncException(SyncException.NETWORK_ERROR,
                    "Failed to create default containers: " + errors.toString());
        }
        Log.d(TAG, "ensureDefaultContainers: created " + created + " containers");
    }

    /**
     * PUT a file to the repository (create or update).
     *
     * @param path    file path within the repo
     * @param content file content string
     * @param message commit message
     * @param sha     existing blob SHA (required for updates, null for creates)
     * @return the API response JSON object
     */
    private JSONObject putFile(String path, String content, String message, String sha)
            throws SyncException, JSONException {
        JSONObject body = new JSONObject();
        body.put("message", message);
        body.put("content", Base64.encodeToString(
                content.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP));
        body.put("branch", getDefaultBranch());

        if (!TextUtils.isEmpty(sha)) {
            body.put("sha", sha);
        }

        String response = apiPut("/repos/" + mOwner + "/" + mRepo
                + "/contents/" + urlEncode(path), body.toString());

        JSONObject result = new JSONObject(response);
        JSONObject contentObj = result.optJSONObject("content");
        if (contentObj != null) {
            String newSha = contentObj.optString("sha", "");
            mShaCache.put(path, newSha);
        }

        return result;
    }

    /**
     * Decode base64 content from a GitHub API file response.
     */
    private String decodeContent(JSONObject fileObj) throws JSONException {
        String encoding = fileObj.optString("encoding", "");
        String content = fileObj.optString("content", "");

        if ("base64".equals(encoding) && !TextUtils.isEmpty(content)) {
            // Remove newlines (GitHub may include them in base64 content)
            String cleaned = content.replaceAll("\\s", "");
            byte[] decoded = Base64.decode(cleaned, Base64.DEFAULT);
            return new String(decoded, StandardCharsets.UTF_8);
        }

        return content;
    }

    /**
     * Get the default branch for the repository (usually "main" or "master").
     */
    private String getDefaultBranch() {
        // For simplicity, we assume "main". A more robust approach
        // would cache this from the repo info query.
        return "main";
    }

    // ── HTTP methods ───────────────────────────────────────────────

    private Request.Builder buildRequest(String path) {
        return new Request.Builder()
                .url(GITHUB_API + path)
                .header("Authorization", "Bearer " + mToken)
                .header("Accept", "application/vnd.github.v3+json")
                .header("User-Agent", "Notesmaster/1.0");
    }

    private String apiGet(String path) throws SyncException {
        Log.d(TAG, "GET " + path);
        Request request = buildRequest(path).get().build();
        return execute(request);
    }

    private String apiPut(String path, String jsonBody) throws SyncException {
        Log.d(TAG, "PUT " + path);
        RequestBody body = RequestBody.create(jsonBody, JSON_MEDIA_TYPE);
        Request request = buildRequest(path).put(body).build();
        return execute(request);
    }

    private String apiDelete(String path, String jsonBody) throws SyncException {
        Log.d(TAG, "DELETE " + path);
        RequestBody body = RequestBody.create(jsonBody, JSON_MEDIA_TYPE);
        Request request = buildRequest(path).delete(body).build();
        return execute(request);
    }

    private String execute(Request request) throws SyncException {
        try {
            Response response = mHttpClient.newCall(request).execute();
            String responseBody = response.body() != null
                    ? response.body().string() : "";

            if (response.code() == 401) {
                response.close();
                throw new SyncException(SyncException.AUTH_ERROR,
                        "Authentication failed. Check your GitHub token.");
            }

            if (response.code() == 404) {
                response.close();
                throw new SyncException(SyncException.DATA_ERROR,
                        "Resource not found: " + request.url().encodedPath());
            }

            if (response.code() == 409) {
                response.close();
                throw new SyncException(SyncException.CONFLICT,
                        "Conflict: the file has been modified by another device.");
            }

            if (!response.isSuccessful()) {
                String errorMsg = responseBody;
                try {
                    JSONObject errObj = new JSONObject(responseBody);
                    errorMsg = errObj.optString("message", responseBody);
                } catch (JSONException ignored) {
                }
                response.close();
                throw new SyncException(SyncException.NETWORK_ERROR,
                        "HTTP " + response.code() + ": " + errorMsg);
            }

            response.close();
            return responseBody;

        } catch (IOException e) {
            throw new SyncException(SyncException.NETWORK_ERROR,
                    "Network error: " + e.getMessage(), e);
        }
    }

    // ── JSON builders ──────────────────────────────────────────────

    private JSONObject buildContainerJson(String path, String name, JSONObject dirItem)
            throws JSONException {
        JSONObject container = new JSONObject();
        container.put(GTaskStringUtils.GTASK_JSON_ID, path);
        container.put(GTaskStringUtils.GTASK_JSON_NAME, name);
        // Use sha as a proxy for last_modified if no timestamp available
        container.put(GTaskStringUtils.GTASK_JSON_LAST_MODIFIED, System.currentTimeMillis());
        return container;
    }

    // ── URL encoding ───────────────────────────────────────────────

    /**
     * Encode a path segment for GitHub API, preserving slashes.
     */
    private static String urlEncode(String path) {
        if (path == null) return "";
        String[] segments = path.split("/");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < segments.length; i++) {
            if (i > 0) sb.append("/");
            // UTF-8 is always available on Android, so this won't throw
            try {
                sb.append(java.net.URLEncoder.encode(segments[i], "UTF-8")
                        .replace("+", "%20")
                        .replace("%40", "@"));
            } catch (Exception e) {
                sb.append(segments[i]);
            }
        }
        return sb.toString();
    }

    /**
     * Sanitize a note name/snippet for use as a filename.
     * Preserves Unicode (Chinese, Japanese, etc.) but replaces
     * filesystem-unsafe characters with underscores.
     */
    private static String sanitizeFilename(String name) {
        if (TextUtils.isEmpty(name)) return "";
        // Replace characters unsafe for most filesystems
        // Keep: letters, digits, Chinese chars, underscores, hyphens, dots
        String sanitized = name
                .replaceAll("[/\\\\:*?\"<>|#%&{}~^\\s]+", "_")
                .replaceAll("_+", "_")          // collapse multiple underscores
                .replaceAll("^_|_$", "");        // trim leading/trailing underscores
        // Limit length to avoid path-too-long issues
        if (sanitized.length() > 100) {
            sanitized = sanitized.substring(0, 100);
        }
        return sanitized;
    }
}
