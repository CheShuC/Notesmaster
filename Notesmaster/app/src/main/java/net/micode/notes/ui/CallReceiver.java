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

package net.micode.notes.ui;

import android.appwidget.AppWidgetManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.telephony.TelephonyManager;
import android.util.Log;

import net.micode.notes.R;
import net.micode.notes.data.Notes;
import net.micode.notes.model.WorkingNote;
import net.micode.notes.tool.DataUtils;
import net.micode.notes.tool.ResourceParser;

public class CallReceiver extends BroadcastReceiver {
    private static final String TAG = "CallReceiver";

    private static String sPendingNumber;

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        Log.w(TAG, "onReceive action=" + action);

        if (TelephonyManager.ACTION_PHONE_STATE_CHANGED.equals(action)) {
            String state = intent.getStringExtra(TelephonyManager.EXTRA_STATE);
            Log.w(TAG, "PHONE_STATE=" + state);

            if (TelephonyManager.EXTRA_STATE_RINGING.equals(state)) {
                sPendingNumber = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER);
                Log.w(TAG, "RINGING number=" + sPendingNumber);
            } else if (TelephonyManager.EXTRA_STATE_IDLE.equals(state)) {
                Log.w(TAG, "IDLE pendingNumber=" + sPendingNumber);
                if (sPendingNumber != null && sPendingNumber.length() > 0) {
                    createCallNote(context, sPendingNumber);
                    sPendingNumber = null;
                }
            }
        } else if (Intent.ACTION_NEW_OUTGOING_CALL.equals(action)) {
            String number = intent.getStringExtra(Intent.EXTRA_PHONE_NUMBER);
            Log.w(TAG, "OUTGOING_CALL number=" + number);
            if (number != null && number.length() > 0) {
                createCallNote(context, number);
            }
        }
    }

    static void createCallNote(Context context, String phoneNumber) {
        long callDate = System.currentTimeMillis();
        Log.w(TAG, "createCallNote phone=" + phoneNumber + " date=" + callDate);

        long noteId = DataUtils.getNoteIdByPhoneNumberAndCallDate(
                context.getContentResolver(), phoneNumber, callDate);
        if (noteId > 0) {
            Log.w(TAG, "Call note already exists, noteId=" + noteId);
            return;
        }

        try {
            int bgColor = ResourceParser.getDefaultBgId(context);
            Log.w(TAG, "Creating note with bgColor=" + bgColor);
            WorkingNote note = WorkingNote.createEmptyNote(context,
                    Notes.ID_CALL_RECORD_FOLDER,
                    AppWidgetManager.INVALID_APPWIDGET_ID,
                    Notes.TYPE_WIDGET_INVALIDE,
                    bgColor);
            note.convertToCallNote(phoneNumber, callDate);

            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault());
            String timeStr = sdf.format(new java.util.Date(callDate));
            note.setWorkingText(context.getString(R.string.note_created_from_call, timeStr));

            boolean saved = note.saveNote();
            Log.w(TAG, "Call note saved=" + saved);
        } catch (Exception e) {
            Log.e(TAG, "Failed to create call note: " + e.getMessage(), e);
        }
    }
}
