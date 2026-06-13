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
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;

import net.micode.notes.R;
import net.micode.notes.ui.NotesListActivity;

public class GTaskSyncService extends Service {
    public final static String ACTION_STRING_NAME = "sync_action_type";

    public final static int ACTION_START_SYNC = 0;

    public final static int ACTION_CANCEL_SYNC = 1;

    public final static int ACTION_INVALID = 2;

    public final static String EXTRA_SYNC_MODE = "sync_mode";

    public final static String GTASK_SERVICE_BROADCAST_NAME = "net.micode.notes.gtask.remote.gtask_sync_service";

    public final static String GTASK_SERVICE_BROADCAST_IS_SYNCING = "isSyncing";

    public final static String GTASK_SERVICE_BROADCAST_PROGRESS_MSG = "progressMsg";

    /** Notification channel ID used for both foreground service and progress updates */
    static final String SYNC_CHANNEL_ID = "sync_channel";

    /** Notification ID for the foreground service (same as GTaskASyncTask uses) */
    private static final int SYNC_NOTIFICATION_ID = 5234235;

    private static GTaskASyncTask mSyncTask = null;

    private static String mSyncProgress = "";

    private NotificationManager mNotifiManager;

    private void startSync(int syncMode) {
        if (mSyncTask == null) {
            mSyncTask = new GTaskASyncTask(this, syncMode, new GTaskASyncTask.OnCompleteListener() {
                public void onComplete() {
                    mSyncTask = null;
                    sendBroadcast("");
                    stopForeground(true);  // remove foreground notification
                    stopSelf();
                }
            });
            sendBroadcast("");

            // Promote to foreground service so the system won't kill us mid-sync.
            // This is REQUIRED on Android 8.0+ for long-running services.
            Notification notification = buildForegroundNotification(
                    getString(R.string.sync_progress_login, ""));
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(SYNC_NOTIFICATION_ID, notification,
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(SYNC_NOTIFICATION_ID, notification);
            }
            mSyncTask.execute();
        }
    }

    private void cancelSync() {
        if (mSyncTask != null) {
            mSyncTask.cancelSync();
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        mSyncTask = null;
        mNotifiManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        createSyncChannel();
    }

    /**
     * Create the notification channel once, at service creation time.
     * This ensures the channel exists before any notification (including
     * the foreground-service one) is posted.
     */
    private void createSyncChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    SYNC_CHANNEL_ID,
                    getString(R.string.menu_sync),
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription(getString(R.string.sync_progress_init_list));
            mNotifiManager.createNotificationChannel(channel);
        }
    }

    /**
     * Build a minimal notification used for the foreground service.
     * The async task will replace this with richer content as sync progresses.
     */
    private Notification buildForegroundNotification(String content) {
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, new Intent(this, NotesListActivity.class),
                PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, SYNC_CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }
        return builder
                .setSmallIcon(R.drawable.notification)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(content)
                .setContentIntent(pendingIntent)
                .setWhen(System.currentTimeMillis())
                .setOngoing(true)
                .build();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            return START_STICKY;
        }
        Bundle bundle = intent.getExtras();
        if (bundle != null && bundle.containsKey(ACTION_STRING_NAME)) {
            switch (bundle.getInt(ACTION_STRING_NAME, ACTION_INVALID)) {
                case ACTION_START_SYNC:
                    int syncMode = bundle.getInt(EXTRA_SYNC_MODE,
                            GTaskManager.SYNC_MODE_BIDIRECTIONAL);
                    startSync(syncMode);
                    break;
                case ACTION_CANCEL_SYNC:
                    cancelSync();
                    break;
                default:
                    break;
            }
            return START_STICKY;
        }
        return super.onStartCommand(intent, flags, startId);
    }

    @Override
    public void onLowMemory() {
        if (mSyncTask != null) {
            mSyncTask.cancelSync();
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    public void sendBroadcast(String msg) {
        mSyncProgress = msg;
        Intent intent = new Intent(GTASK_SERVICE_BROADCAST_NAME);
        intent.putExtra(GTASK_SERVICE_BROADCAST_IS_SYNCING, mSyncTask != null);
        intent.putExtra(GTASK_SERVICE_BROADCAST_PROGRESS_MSG, msg);
        sendBroadcast(intent);
    }

    public static void startSync(Activity activity, int syncMode) {
        GTaskManager.getInstance().setActivityContext(activity);
        Intent intent = new Intent(activity, GTaskSyncService.class);
        intent.putExtra(GTaskSyncService.ACTION_STRING_NAME, GTaskSyncService.ACTION_START_SYNC);
        intent.putExtra(GTaskSyncService.EXTRA_SYNC_MODE, syncMode);
        activity.startService(intent);
    }

    public static void cancelSync(Context context) {
        Intent intent = new Intent(context, GTaskSyncService.class);
        intent.putExtra(GTaskSyncService.ACTION_STRING_NAME, GTaskSyncService.ACTION_CANCEL_SYNC);
        context.startService(intent);
    }

    public static boolean isSyncing() {
        return mSyncTask != null;
    }

    public static String getProgressString() {
        return mSyncProgress;
    }
}
