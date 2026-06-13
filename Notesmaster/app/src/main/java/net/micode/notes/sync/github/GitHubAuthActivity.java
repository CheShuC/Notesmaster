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

import android.app.AlertDialog;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.AsyncTask;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import net.micode.notes.R;
import net.micode.notes.sync.SyncClientFactory;
import net.micode.notes.sync.SyncException;

import java.util.Locale;

/**
 * Activity for configuring GitHub sync authentication.
 * <p>
 * Provides a step-by-step guide (in Chinese) for obtaining a GitHub
 * Personal Access Token, testing the connection, and saving the config.
 */
public class GitHubAuthActivity extends android.app.Activity {

    private EditText mTokenInput;

    private TextView mStatusText;

    private Button mTestButton;

    private Button mSaveButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 强制使用中文显示引导页
        forceChineseLocale();

        setContentView(R.layout.github_auth);

        mTokenInput = (EditText) findViewById(R.id.github_token_input);
        mStatusText = (TextView) findViewById(R.id.github_status);
        mTestButton = (Button) findViewById(R.id.github_test_button);
        mSaveButton = (Button) findViewById(R.id.github_save_button);

        // Load existing token
        String existingToken = GitHubSyncClient.getAuthToken(this);
        if (!TextUtils.isEmpty(existingToken)) {
            mTokenInput.setText(existingToken);
        }

        // Show current status
        String owner = GitHubSyncClient.getOwner(this);
        if (!TextUtils.isEmpty(owner)) {
            mStatusText.setText(getString(R.string.preferences_github_connected_as, owner));
            mStatusText.setVisibility(View.VISIBLE);
        }

        mTestButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                testConnection();
            }
        });

        mSaveButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveAndFinish();
            }
        });
    }

    private void testConnection() {
        final String token = mTokenInput.getText().toString().trim();
        if (TextUtils.isEmpty(token)) {
            Toast.makeText(this, R.string.preferences_github_token_empty,
                    Toast.LENGTH_SHORT).show();
            return;
        }

        mTestButton.setEnabled(false);
        mStatusText.setVisibility(View.VISIBLE);
        mStatusText.setText(R.string.preferences_github_testing);

        new AsyncTask<Void, Void, String>() {
            private int mErrorResId = 0;

            @Override
            protected String doInBackground(Void... params) {
                try {
                    // Temporarily save token and try login
                    String savedToken = GitHubSyncClient.getAuthToken(GitHubAuthActivity.this);
                    GitHubSyncClient.setAuthToken(GitHubAuthActivity.this, token);
                    GitHubSyncClient.setOwner(GitHubAuthActivity.this, ""); // force re-query

                    GitHubSyncClient client = GitHubSyncClient.getInstance(
                            GitHubAuthActivity.this);
                    boolean ok = client.login(GitHubAuthActivity.this);

                    if (!ok) {
                        // Restore old token
                        GitHubSyncClient.setAuthToken(GitHubAuthActivity.this, savedToken);
                        mErrorResId = R.string.preferences_github_test_failed_auth;
                        return null;
                    }

                    return client.getAccountIdentifier();
                } catch (SyncException e) {
                    mErrorResId = R.string.preferences_github_test_failed_network;
                    return null;
                }
            }

            @Override
            protected void onPostExecute(String username) {
                mTestButton.setEnabled(true);
                if (username != null) {
                    mStatusText.setText(getString(
                            R.string.preferences_github_connected_as, username));
                    Toast.makeText(GitHubAuthActivity.this,
                            R.string.preferences_github_test_success,
                            Toast.LENGTH_SHORT).show();
                } else {
                    mStatusText.setText(getString(mErrorResId));
                    Toast.makeText(GitHubAuthActivity.this,
                            mErrorResId, Toast.LENGTH_SHORT).show();
                }
            }
        }.execute();
    }

    /**
     * 强制使用中文语言环境显示引导页面。
     */
    private void forceChineseLocale() {
        Resources resources = getResources();
        Configuration config = resources.getConfiguration();
        config.setLocale(Locale.SIMPLIFIED_CHINESE);
        resources.updateConfiguration(config, resources.getDisplayMetrics());
    }

    private void saveAndFinish() {
        String token = mTokenInput.getText().toString().trim();
        if (TextUtils.isEmpty(token)) {
            Toast.makeText(this, R.string.preferences_github_token_empty,
                    Toast.LENGTH_SHORT).show();
            return;
        }

        // Save token
        GitHubSyncClient.setAuthToken(this, token);

        // Set GitHub as the sync provider
        SyncClientFactory.setSyncProvider(this, SyncClientFactory.PROVIDER_GITHUB);

        Toast.makeText(this, R.string.preferences_github_saved,
                Toast.LENGTH_SHORT).show();
        finish();
    }
}
