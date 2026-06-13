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

package net.micode.notes.sync;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import net.micode.notes.sync.github.GitHubSyncClient;

/**
 * Factory that returns the configured {@link SyncClient} implementation.
 * <p>
 * The active provider is stored in SharedPreferences under key
 * {@code pref_sync_provider}. Currently supported values:
 * <ul>
 *   <li>{@code "github"} — GitHub private repository sync</li>
 * </ul>
 */
public class SyncClientFactory {

    private static final String PREFERENCE_NAME = "notes_preferences";

    public static final String SYNC_PROVIDER_KEY = "pref_sync_provider";

    public static final String PROVIDER_GITHUB = "github";

    public static final String PROVIDER_GOOGLE = "google";

    private static SyncClient sInstance;

    /**
     * Get or create the configured SyncClient.
     * Thread-safe: synchronizes on the factory to prevent double creation.
     *
     * @param context Android context
     * @return the SyncClient instance, or null if no valid provider is configured
     */
    public static synchronized SyncClient getSyncClient(Context context) {
        String provider = getSyncProvider(context);

        if (PROVIDER_GITHUB.equals(provider)) {
            if (sInstance == null || !(sInstance instanceof GitHubSyncClient)) {
                sInstance = GitHubSyncClient.getInstance(context);
            }
            return sInstance;
        }

        // No valid provider configured
        return null;
    }

    /**
     * @return the configured sync provider key, or empty string if none
     */
    public static String getSyncProvider(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFERENCE_NAME,
                Context.MODE_PRIVATE);
        return prefs.getString(SYNC_PROVIDER_KEY, "");
    }

    /**
     * Set the active sync provider.
     */
    public static synchronized void setSyncProvider(Context context, String provider) {
        SharedPreferences prefs = context.getSharedPreferences(PREFERENCE_NAME,
                Context.MODE_PRIVATE);
        prefs.edit().putString(SYNC_PROVIDER_KEY, provider).commit();
        // Clear all cached instances so the next getSyncClient creates the right type
        sInstance = null;
        GitHubSyncClient.resetInstance();
    }

    /**
     * Check if a sync provider is configured and has valid credentials.
     */
    public static boolean isSyncConfigured(Context context) {
        String provider = getSyncProvider(context);
        if (PROVIDER_GITHUB.equals(provider)) {
            return !TextUtils.isEmpty(GitHubSyncClient.getAuthToken(context))
                    && !TextUtils.isEmpty(GitHubSyncClient.getRepository(context));
        }
        return false;
    }
}
