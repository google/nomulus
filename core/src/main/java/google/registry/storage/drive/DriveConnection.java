// Copyright 2017 The Nomulus Authors. All Rights Reserved.
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

import com.google.api.client.http.ByteArrayContent;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.Drive.Children;
import com.google.api.services.drive.model.ChildList;
import com.google.api.services.drive.model.ChildReference;
import com.google.api.services.drive.model.File;
import com.google.api.services.drive.model.ParentReference;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.net.MediaType;
import java.io.IOException;
import java.util.List;
import javax.annotation.Nullable;
import javax.inject.Inject;

/** Class encapsulating parameters and state for accessing the Drive API. */
public class DriveConnection {

  private static final MediaType GOOGLE_FOLDER =
      MediaType.create("application", "vnd.google-apps.folder");

  /** Drive client instance wrapped by this class. */
  @Inject Drive drive;
  @Inject public DriveConnection() {}

  /**
   * Creates a folder with the given parent.
   *
   * @return the folder id.
   */
  public String createFolder(String title, String parentFolderId) throws IOException {
    return drive.files()
        .insert(createFileReference(title, GOOGLE_FOLDER, parentFolderId))
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
  public String createFile(String title, MediaType mimeType, String parentFolderId, byte[] bytes)
      throws IOException {
    return drive.files()
        .insert(
            createFileReference(title, mimeType, parentFolderId),
            new ByteArrayContent(mimeType.toString(), bytes))
        .execute()
        .getId();
  }

  /**
   * Creates a file with the given parent or updates the existing one if a file already exists with
   * that same title and parent.
   *
   * @throws IllegalStateException if multiple files with that name exist in the given folder.
   * @throws IOException if communication with Google Drive fails for any reason.
   * @return the file id.
   */
  public String createOrUpdateFile(
      String title, MediaType mimeType, String parentFolderId, byte[] bytes) throws IOException {
    List<String> existingFiles = listFiles(parentFolderId, String.format("title = '%s'", title));
    if (existingFiles.size() > 1) {
      throw new IllegalStateException(String.format(
          "Could not update file '%s' in Drive folder id '%s' because multiple files with that "
              + "name already exist.",
          title,
          parentFolderId));
    }
    return existingFiles.isEmpty()
        ? createFile(title, mimeType, parentFolderId, bytes)
        : updateFile(existingFiles.get(0), title, mimeType, bytes);
  }

  /**
   * Updates the file with the given id in place, setting the title, content, and mime type to the
   * newly specified values.
   *
   * @return the file id.
   */
  public String updateFile(String fileId, String title, MediaType mimeType, byte[] bytes)
      throws IOException {
    File file = new File().setTitle(title);
    return drive.files()
        .update(fileId, file, new ByteArrayContent(mimeType.toString(), bytes))
        .execute()
        .getId();
  }
  /**
   * Returns a list of Drive file ids for all files in Google Drive in the folder with the
   * specified id.
   */
  public List<String> listFiles(String parentFolderId) throws IOException {
    return listFiles(parentFolderId, null);
  }

  /**
   * Returns a list of Drive file ids for all files in Google Drive in the folder with the
   * specified id and matching the given Drive query.
   *
   * @see <a href="https://developers.google.com/drive/web/search-parameters">The query format</a>
   */
  public List<String> listFiles(String parentFolderId, String query) throws IOException {
    ImmutableList.Builder<String> result = new ImmutableList.Builder<>();
    Children.List req = drive.children().list(parentFolderId);
    if (!Strings.isNullOrEmpty(query)) {
      req.setQ(query);
    }
    do {
      ChildList files = req.execute();
      for (ChildReference child : files.getItems()) {
        result.add(child.getId());
      }
      req.setPageToken(files.getNextPageToken());
    } while (!Strings.isNullOrEmpty(req.getPageToken()));
    return result.build();
  }

  /** Constructs an object representing a file (or folder) with a given title and parent. */
  private File createFileReference(
      String title, MediaType mimeType, @Nullable String parentFolderId) {
    return new File()
        .setTitle(title)
        .setMimeType(mimeType.toString())
        .setParents(parentFolderId == null
            ? null
            : ImmutableList.of(new ParentReference().setId(parentFolderId)));
  }
}
