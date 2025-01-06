// Copyright 2025 The Nomulus Authors. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package google.registry.storage.drive;

import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.client.http.ByteArrayContent;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.Drive.Files;
import com.google.api.services.drive.model.File;
import com.google.api.services.drive.model.FileList;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.flogger.FluentLogger;
import com.google.common.net.MediaType;
import java.io.IOException;
import java.util.List;
import javax.annotation.Nullable;
import javax.inject.Inject;

/** Class encapsulating parameters and state for accessing the Drive API. */
public class DriveConnection {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  /** Number of times a request to Drive will be retried before propagating a failure. */
  private static final int MAX_RETRIES = 10;

  private static final MediaType GOOGLE_FOLDER =
      MediaType.create("application", "vnd.google-apps.folder");

  /** Drive client instance wrapped by this class. */
  @Inject Drive drive;

  @Inject
  public DriveConnection() {}

  /**
   * Creates a folder with the given parent.
   *
   * @return the folder id.
   */
  public String createFolder(String name, @Nullable String parentFolderId) throws IOException {
    return drive
        .files()
        .create(createFileReference(name, GOOGLE_FOLDER, parentFolderId))
        .execute()
        .getId();
  }

  /**
   * Creates a file with the given parent.
   *
   * <p>If a file with the same path already exists, a duplicate is created. If overwriting the
   * existing file is the desired behavior, use {@link #createOrUpdateFile(String, MediaType,
   * String, byte[])} instead.
   *
   * @return the file id.
   */
  public String createFile(String name, MediaType mimeType, String parentFolderId, byte[] bytes)
      throws IOException {
    return drive
        .files()
        .create(
            createFileReference(name, mimeType, parentFolderId),
            new ByteArrayContent(mimeType.toString(), bytes))
        .execute()
        .getId();
  }

  /**
   * Creates a file with the given parent or updates the existing one if a file already exists with
   * that same name and parent.
   *
   * @return the file id.
   * @throws IllegalStateException if multiple files with that name exist in the given folder.
   * @throws IOException if communication with Google Drive fails for any reason.
   */
  public String createOrUpdateFile(
      String name, MediaType mimeType, String parentFolderId, byte[] bytes) throws IOException {
    List<String> existingFiles = listFiles(parentFolderId, String.format("name = '%s'", name));
    if (existingFiles.size() > 1) {
      throw new IllegalStateException(
          String.format(
              "Could not update file '%s' in Drive folder id '%s' because multiple files with that "
                  + "name already exist.",
              name, parentFolderId));
    }
    return existingFiles.isEmpty()
        ? createFile(name, mimeType, parentFolderId, bytes)
        : updateFile(existingFiles.getFirst(), name, mimeType, bytes);
  }

  /**
   * Updates the file with the given id in place, setting the name, content, and mime type to the
   * newly specified values.
   *
   * @return the file id.
   */
  public String updateFile(String fileId, String name, MediaType mimeType, byte[] bytes)
      throws IOException {
    File file = new File().setName(name);
    return drive
        .files()
        .update(fileId, file, new ByteArrayContent(mimeType.toString(), bytes))
        .execute()
        .getId();
  }

  /**
   * Returns a list of Drive file ids for all files in Google Drive in the folder with the specified
   * id.
   */
  public List<String> listFiles(String parentFolderId) throws IOException {
    return listFiles(parentFolderId, null);
  }

  /**
   * Returns a list of Drive file ids for all files in Google Drive in the folder with the specified
   * id and matching the given Drive query.
   *
   * @see <a href="https://developers.google.com/drive/web/search-parameters">The query format</a>
   */
  public List<String> listFiles(String parentFolderId, String query) throws IOException {
    int failures = 0;
    boolean latestRequestFailed;
    ImmutableList.Builder<String> result = new ImmutableList.Builder<>();
    Files.List req = drive.files().list();
    StringBuilder q = new StringBuilder(String.format("'%s' in parents", parentFolderId));
    if (!Strings.isNullOrEmpty(query)) {
      q.append(String.format(" and %s", query));
    }
    req.setQ(q.toString());
    do {
      try {
        latestRequestFailed = false;
        FileList files = req.execute();
        files.getFiles().forEach(file -> result.add(file.getId()));
        req.setPageToken(files.getNextPageToken());
      } catch (GoogleJsonResponseException e) {
        if (failures >= MAX_RETRIES) {
          throw new RuntimeException(
              String.format(
                  "Max failures reached while attempting to list Drive files in folder %s with "
                      + "query %s; failing permanently.",
                  parentFolderId, query),
              e);
        }
        latestRequestFailed = true;
        logger.atWarning().withCause(e).log(
            "Attempt: %d. Failed to list files from Drive. Folder: %s, query: %s.",
            failures, parentFolderId, query);
        failures++;
      }
    } while (!Strings.isNullOrEmpty(req.getPageToken()) || latestRequestFailed);
    return result.build();
  }

  /** Constructs an object representing a file (or folder) with a given name and parent. */
  private File createFileReference(
      String name, MediaType mimeType, @Nullable String parentFolderId) {
    return new File()
        .setName(name)
        .setMimeType(mimeType.toString())
        .setParents(parentFolderId == null ? null : ImmutableList.of(parentFolderId));
  }
}
