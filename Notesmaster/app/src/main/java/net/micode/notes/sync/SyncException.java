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

/**
 * Unified exception for sync operations, replacing Google-specific
 * {@code NetworkFailureException} and {@code ActionFailureException}.
 */
public class SyncException extends Exception {

    public static final int NETWORK_ERROR = 1;

    public static final int AUTH_ERROR = 2;

    public static final int DATA_ERROR = 3;

    public static final int CONFLICT = 4;

    private int mErrorCode;

    public SyncException(int errorCode, String message) {
        super(message);
        mErrorCode = errorCode;
    }

    public SyncException(int errorCode, String message, Throwable cause) {
        super(message, cause);
        mErrorCode = errorCode;
    }

    public int getErrorCode() {
        return mErrorCode;
    }
}
