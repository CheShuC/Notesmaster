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

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import net.micode.notes.R;
import net.micode.notes.ui.NotesListActivity;
import net.micode.notes.ui.NotesPreferenceActivity;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;


public class GTaskASyncTask extends AsyncTask<Void, String, Integer> {

    private static final String TAG = "GTaskASyncTask";

    // Must match GTaskSyncService.SYNC_NOTIFICATION_ID so we update the same
    // foreground-service notification rather than posting a second one.
    private static final int GTASK_SYNC_NOTIFICATION_ID = 5234235;

    public interface OnCompleteListener {
        void onComplete();
    }

    private Context mContext;

    private NotificationManager mNotifiManager;

    private GTaskManager mTaskManager;

    private OnCompleteListener mOnCompleteListener;

    private Handler mHandler;

    private int mSyncMode;

    public GTaskASyncTask(Context context, int syncMode, OnCompleteListener listener) {
        mContext = context;
        mSyncMode = syncMode;
        mOnCompleteListener = listener;
        mNotifiManager = (NotificationManager) mContext
                .getSystemService(Context.NOTIFICATION_SERVICE);
        mTaskManager = GTaskManager.getInstance();
        mHandler = new Handler(Looper.getMainLooper());
    }

    public void cancelSync() {
        mTaskManager.cancelSync();
    }

    public void publishProgess(String message) {
        publishProgress(new String[] {
            message
        });
    }

    private void showNotification(int tickerId, String content) {
        PendingIntent pendingIntent;
        if (tickerId != R.string.ticker_success) {
            pendingIntent = PendingIntent.getActivity(mContext, 0, new Intent(mContext,
                    NotesPreferenceActivity.class), PendingIntent.FLAG_IMMUTABLE);
        } else {
            pendingIntent = PendingIntent.getActivity(mContext, 0, new Intent(mContext,
                    NotesListActivity.class), PendingIntent.FLAG_IMMUTABLE);
        }

        // Channel is created once by GTaskSyncService.onCreate() — reuse it.
        String channelId = GTaskSyncService.SYNC_CHANNEL_ID;

        try {
            Notification.Builder builder;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                builder = new Notification.Builder(mContext, channelId);
            } else {
                builder = new Notification.Builder(mContext);
            }
            Notification notification = builder
                    .setSmallIcon(R.drawable.notification)
                    .setAutoCancel(true)
                    .setContentTitle(mContext.getString(R.string.app_name))
                    .setContentText(content)
                    .setContentIntent(pendingIntent)
                    .setWhen(System.currentTimeMillis())
                    .setOngoing(true)
                    .build();
            mNotifiManager.notify(GTASK_SYNC_NOTIFICATION_ID, notification);
        } catch (Exception e) {
            Log.w(TAG, "Failed to show notification: " + e.getMessage());
        }
    }

    private void showToast(final String message) {
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(mContext, message, Toast.LENGTH_SHORT).show();
            }
        });
    }

    @Override
    protected void onPreExecute() {
        Log.d(TAG, "Sync starting...");
        showToast(mContext.getString(R.string.ticker_syncing));
    }

    @Override
    protected Integer doInBackground(Void... unused) {
        try {
            // Get account name for progress display (before sync sets up mContext)
            String accountName = NotesPreferenceActivity.getSyncAccountName(mContext);
            if (accountName == null || accountName.trim().length() == 0) {
                net.micode.notes.sync.SyncClient client =
                        net.micode.notes.sync.SyncClientFactory.getSyncClient(mContext);
                if (client != null) {
                    accountName = client.getAccountIdentifier();
                }
            }
            if (accountName == null || accountName.trim().length() == 0) {
                accountName = "GitHub";
            }
            Log.d(TAG, "Sync: logging in as " + accountName);
            publishProgess(mContext.getString(R.string.sync_progress_login, accountName));
            Log.d(TAG, "Sync: entering GTaskManager.sync() mode=" + mSyncMode);
            return mTaskManager.sync(mContext, this, mSyncMode);
        } catch (Throwable e) {
            // Catch Throwable (not just Exception) so OutOfMemoryError,
            // NoClassDefFoundError, etc. don't crash the app.
            Log.e(TAG, "Sync crashed: " + e.getMessage(), e);
            String crashMsg = e.getClass().getSimpleName() + ": " + e.getMessage();
            publishProgess("同步崩溃: " + crashMsg);
            // Write crash log to file so user can read it after crash
            writeCrashLog(e);
            return GTaskManager.STATE_INTERNAL_ERROR;
        }
    }

    private void writeCrashLog(Throwable e) {
        try {
            File dir = mContext.getFilesDir();
            File logFile = new File(dir, "sync_crash.log");
            FileWriter fw = new FileWriter(logFile, false); // overwrite each time
            PrintWriter pw = new PrintWriter(fw);
            pw.println("=== " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                    .format(new Date()) + " ===");
            pw.println("Error: " + e.getClass().getName() + ": " + e.getMessage());
            pw.println();
            e.printStackTrace(pw);
            pw.println();
            pw.println("=== Cause ===");
            Throwable cause = e.getCause();
            while (cause != null) {
                pw.println("Caused by: " + cause.getClass().getName() + ": " + cause.getMessage());
                cause.printStackTrace(pw);
                pw.println();
                cause = cause.getCause();
            }
            pw.flush();
            pw.close();
            Log.d(TAG, "Crash log written to " + logFile.getAbsolutePath()
                    + " size=" + logFile.length());
        } catch (Throwable ignored) {
            Log.w(TAG, "Could not write crash log: " + ignored.getMessage());
        }
    }

    @Override
    protected void onProgressUpdate(String... progress) {
        Log.d(TAG, "Sync progress: " + progress[0]);
        showNotification(R.string.ticker_syncing, progress[0]);
        showToast(progress[0]);
        if (mContext instanceof GTaskSyncService) {
            ((GTaskSyncService) mContext).sendBroadcast(progress[0]);
        }
    }

    @Override
    protected void onPostExecute(Integer result) {
        Log.d(TAG, "Sync finished with result: " + result);
        if (result == GTaskManager.STATE_SUCCESS) {
            showNotification(R.string.ticker_success, mContext.getString(
                    R.string.success_sync_account, mTaskManager.getSyncAccount()));
            showToast(mContext.getString(R.string.ticker_success));
            NotesPreferenceActivity.setLastSyncTime(mContext, System.currentTimeMillis());
        } else if (result == GTaskManager.STATE_NETWORK_ERROR) {
            showNotification(R.string.ticker_fail, mContext.getString(R.string.error_sync_network));
            showToast(mContext.getString(R.string.error_sync_network));
        } else if (result == GTaskManager.STATE_INTERNAL_ERROR) {
            showNotification(R.string.ticker_fail, mContext.getString(R.string.error_sync_internal));
            showToast(mContext.getString(R.string.error_sync_internal));
        } else if (result == GTaskManager.STATE_SYNC_CANCELLED) {
            showNotification(R.string.ticker_cancel, mContext
                    .getString(R.string.error_sync_cancelled));
            showToast(mContext.getString(R.string.error_sync_cancelled));
        } else if (result == GTaskManager.STATE_SYNC_IN_PROGRESS) {
            showToast("同步已在进行中");
        }
        if (mOnCompleteListener != null) {
            new Thread(new Runnable() {
                public void run() {
                    mOnCompleteListener.onComplete();
                }
            }).start();
        }
    }
}
