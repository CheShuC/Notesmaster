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

import net.micode.notes.gtask.data.Node;

import org.json.JSONArray;

/**
 * Backend-agnostic sync client interface.
 * <p>
 * Replaces the Google-specific {@code GTaskClient}. Each implementation
 * (GitHub, WebDAV, etc.) provides its own transport while the sync engine
 * ({@code GTaskManager}) operates against this contract.
 */
public interface SyncClient {

    /**
     * Authenticate to the remote service.
     *
     * @param context Android context (no Activity required for modern auth flows)
     * @return true if login succeeded
     * @throws SyncException on network or auth failure
     */
    boolean login(Context context) throws SyncException;

    /**
     * @return user-visible account identifier (GitHub username, etc.)
     */
    String getAccountIdentifier();

    /**
     * List all remote containers (equivalent to Google Task Lists).
     * Each JSONObject has: "id", "name", "last_modified".
     *
     * @return JSONArray of container objects
     * @throws SyncException on failure
     */
    JSONArray listContainers() throws SyncException;

    /**
     * List all items within a container.
     * Each JSONObject is compatible with {@code Node.setContentByRemoteJSON()},
     * containing: "id", "name", "notes", "last_modified", "deleted".
     *
     * @param containerId remote container identifier
     * @return JSONArray of item objects
     * @throws SyncException on failure
     */
    JSONArray listItems(String containerId) throws SyncException;

    /**
     * Create a new remote container.
     * Sets the remote ID on the Node via {@code node.setGid()}.
     *
     * @param container the container Node to create remotely
     * @return the assigned remote ID (also set on the node)
     * @throws SyncException on failure
     */
    String createContainer(Node container) throws SyncException;

    /**
     * Create a new item within a container.
     * Sets the remote ID on the Node via {@code node.setGid()}.
     *
     * @param containerId parent container remote ID
     * @param item        the item Node to create remotely
     * @return the assigned remote ID (also set on the node)
     * @throws SyncException on failure
     */
    String createItem(String containerId, Node item) throws SyncException;

    /**
     * Update an existing remote item.
     *
     * @param gid  remote item identifier
     * @param item the updated Node data
     * @throws SyncException on failure
     */
    void updateItem(String gid, Node item) throws SyncException;

    /**
     * Delete a remote item.
     *
     * @param gid remote item identifier
     * @throws SyncException on failure
     */
    void deleteItem(String gid) throws SyncException;

    /**
     * Move an item from one container to another.
     *
     * @param gid             remote item identifier
     * @param fromContainerId source container remote ID
     * @param toContainerId   destination container remote ID
     * @throws SyncException on failure
     */
    void moveItem(String gid, String fromContainerId, String toContainerId) throws SyncException;

    /**
     * Flush any batched/pending updates to the remote service.
     * For backends with atomic per-operation semantics (GitHub), this is a no-op.
     *
     * @throws SyncException on failure
     */
    void commitUpdates() throws SyncException;

    /**
     * Reset any pending update state (equivalent to {@code GTaskClient.resetUpdateArray()}).
     */
    void resetUpdateState();
}
