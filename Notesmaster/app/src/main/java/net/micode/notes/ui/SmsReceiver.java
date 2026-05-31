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
import android.os.Bundle;
import android.telephony.SmsMessage;
import android.util.Log;

import net.micode.notes.R;
import net.micode.notes.data.Notes;
import net.micode.notes.model.WorkingNote;
import net.micode.notes.tool.DataUtils;
import net.micode.notes.tool.ResourceParser;

public class SmsReceiver extends BroadcastReceiver {
    private static final String TAG = "SmsReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        Log.w(TAG, "onReceive action=" + action);

        if ("android.provider.Telephony.SMS_RECEIVED".equals(action)) {
            Bundle bundle = intent.getExtras();
            if (bundle != null) {
                Object[] pdus = (Object[]) bundle.get("pdus");
                if (pdus != null) {
                    for (Object pdu : pdus) {
                        SmsMessage smsMessage = SmsMessage.createFromPdu((byte[]) pdu);
                        String sender = smsMessage.getDisplayOriginatingAddress();
                        String body = smsMessage.getDisplayMessageBody();
                        long smsDate = smsMessage.getTimestampMillis();
                        Log.w(TAG, "SMS from=" + sender + " body=" + body + " date=" + smsDate);
                        if (sender != null && sender.length() > 0) {
                            createSmsNote(context, sender, smsDate, body);
                        }
                    }
                }
            }
        }
    }

    static void createSmsNote(Context context, String phoneNumber, long smsDate, String smsBody) {
        Log.w(TAG, "createSmsNote phone=" + phoneNumber + " date=" + smsDate);

        long noteId = DataUtils.getNoteIdByPhoneNumberAndSmsDate(
                context.getContentResolver(), phoneNumber, smsDate);
        if (noteId > 0) {
            Log.w(TAG, "Sms note already exists, noteId=" + noteId);
            return;
        }

        try {
            int bgColor = ResourceParser.getDefaultBgId(context);
            Log.w(TAG, "Creating note with bgColor=" + bgColor);
            WorkingNote note = WorkingNote.createEmptyNote(context,
                    Notes.ID_SMS_RECORD_FOLDER,
                    AppWidgetManager.INVALID_APPWIDGET_ID,
                    Notes.TYPE_WIDGET_INVALIDE,
                    bgColor);
            note.convertToSmsNote(phoneNumber, smsDate, smsBody != null ? smsBody : "");

            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat(
                    "yyyy-MM-dd HH:mm", java.util.Locale.getDefault());
            String timeStr = sdf.format(new java.util.Date(smsDate));
            note.setWorkingText(context.getString(R.string.note_created_from_sms,
                    timeStr, smsBody != null ? smsBody : ""));

            boolean saved = note.saveNote();
            Log.w(TAG, "Sms note saved=" + saved);
        } catch (Exception e) {
            Log.e(TAG, "Failed to create sms note: " + e.getMessage(), e);
        }
    }
}
