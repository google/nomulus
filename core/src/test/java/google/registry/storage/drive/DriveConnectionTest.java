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

import static com.google.common.io.ByteStreams.toByteArray;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.api.client.googleapis.json.GoogleJsonError;
import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.client.http.ByteArrayContent;
import com.google.api.client.http.HttpHeaders;
import com.google.api.client.http.HttpResponseException;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.Drive.Files;
import com.google.api.services.drive.model.File;
import com.google.api.services.drive.model.FileList;
import com.google.common.collect.ImmutableList;
import com.google.common.net.MediaType;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatcher;

/** Tests for {@link DriveConnection}. */
class DriveConnectionTest {

  private final Drive drive = mock(Drive.class);
  private final Files files = mock(Files.class);
  private final Files.Create create = mock(Files.Create.class);
  private final Files.Update update = mock(Files.Update.class);
  private final Files.List filesList = mock(Files.List.class);

  private static final byte[] DATA = {1, 2, 3};
  private DriveConnection driveConnection;
  private List<String> allFiles;

  private ArgumentMatcher<ByteArrayContent> hasByteArrayContent(final byte[] data) {
    return arg -> {
      try {
        return Arrays.equals(data, toByteArray(arg.getInputStream()));
      } catch (Exception e) {
        return false;
      }
    };
  }

  @BeforeEach
  void beforeEach() throws Exception {
    driveConnection = new DriveConnection();
    driveConnection.drive = drive;
    when(drive.files()).thenReturn(files);
    when(create.execute()).thenReturn(new File().setId("id"));
    when(update.execute()).thenReturn(new File().setId("id"));

    // Mocking required for listFiles.
    File file1 = new File().setId("file1");
    File file2 = new File().setId("file2");
    File file3 = new File().setId("file2");
    File file4 = new File().setId("file4");
    List<File> files1 = ImmutableList.of(file1, file2);
    List<File> files2 = ImmutableList.of(file3, file4);
    allFiles = ImmutableList.of(file1.getId(), file2.getId(), file3.getId(), file4.getId());
    FileList fileList1 = new FileList();
    fileList1.setFiles(files1);
    fileList1.setNextPageToken("page2");
    FileList fileList2 = new FileList();
    fileList2.setFiles(files2);
    fileList2.setNextPageToken(null);
    when(filesList.execute()).thenReturn(fileList1, fileList2);
    when(filesList.getPageToken()).thenCallRealMethod();
    when(filesList.setPageToken(any())).thenCallRealMethod();
    when(files.list()).thenReturn(filesList);
  }

  @Test
  void testCreateFileAtRoot() throws Exception {
    when(files.create(
            eq(new File().setName("name").setMimeType("image/gif")),
            argThat(hasByteArrayContent(DATA))))
        .thenReturn(create);
    assertThat(driveConnection.createFile("name", MediaType.GIF, null, DATA)).isEqualTo("id");
  }

  @Test
  void testCreateFileInFolder() throws Exception {
    when(files.create(
            eq(
                new File()
                    .setName("name")
                    .setMimeType("image/gif")
                    .setParents(ImmutableList.of("parent"))),
            argThat(hasByteArrayContent(DATA))))
        .thenReturn(create);
    assertThat(driveConnection.createFile("name", MediaType.GIF, "parent", DATA)).isEqualTo("id");
  }

  @Test
  void testCreateFolderAtRoot() throws Exception {
    when(files.create(new File().setName("name").setMimeType("application/vnd.google-apps.folder")))
        .thenReturn(create);
    assertThat(driveConnection.createFolder("name", null)).isEqualTo("id");
  }

  @Test
  void testCreateFolderInFolder() throws Exception {
    when(files.create(
            new File()
                .setName("name")
                .setMimeType("application/vnd.google-apps.folder")
                .setParents(ImmutableList.of("parent"))))
        .thenReturn(create);
    assertThat(driveConnection.createFolder("name", "parent")).isEqualTo("id");
  }

  @Test
  void testListFiles_noQueryWithPagination() throws Exception {
    assertThat(driveConnection.listFiles("driveFolderId")).containsExactlyElementsIn(allFiles);
    verify(filesList).setPageToken("page2");
    verify(filesList).setPageToken(null);
    verify(filesList, times(1)).setQ("'driveFolderId' in parents");
    verify(filesList, times(2)).getPageToken();
  }

  @Test
  void testListFiles_withQueryAndPagination() throws Exception {
    assertThat(driveConnection.listFiles("driveFolderId", "sampleQuery"))
        .containsExactlyElementsIn(allFiles);
    verify(filesList).setPageToken("page2");
    verify(filesList).setPageToken(null);
    verify(filesList, times(1)).setQ("'driveFolderId' in parents and sampleQuery");
    verify(filesList, times(2)).getPageToken();
  }

  @Test
  void testListFiles_succeedsRetriedGoogleJsonResponseException() throws Exception {
    GoogleJsonResponseException googleJsonResponseException =
        new GoogleJsonResponseException(
            new HttpResponseException.Builder(503, "Service Unavailable.", new HttpHeaders()),
            new GoogleJsonError());
    File testFile = new File().setId("testFile");
    File testFile1 = new File().setId("testFile1");

    when(filesList.execute())
        .thenThrow(googleJsonResponseException)
        .thenReturn(new FileList().setFiles(ImmutableList.of(testFile)).setNextPageToken("next"))
        .thenThrow(googleJsonResponseException)
        .thenReturn(new FileList().setFiles(ImmutableList.of(testFile1)));

    assertThat(driveConnection.listFiles("driveFolderId"))
        .containsExactly(testFile.getId(), testFile1.getId());
    verify(filesList).setPageToken("next");
    verify(filesList, times(1)).setQ("'driveFolderId' in parents");
    verify(filesList, times(4)).getPageToken();
  }

  @Test
  void testListFiles_throwsException_afterMaxRetriedGoogleJsonResponseException() throws Exception {
    GoogleJsonResponseException googleJsonResponseException =
        new GoogleJsonResponseException(
            new HttpResponseException.Builder(503, "Service Unavailable.", new HttpHeaders()),
            new GoogleJsonError());
    when(filesList.execute()).thenThrow(googleJsonResponseException);

    Exception thrown =
        assertThrows(
            Exception.class, () -> driveConnection.listFiles("driveFolderId", "sampleQuery"));
    assertThat(thrown.getCause()).isEqualTo(googleJsonResponseException);
    assertThat(thrown.getMessage())
        .isEqualTo(
            "Max failures reached while attempting to list Drive files in folder "
                + "driveFolderId with query sampleQuery; failing permanently.");

    verify(filesList, times(0)).setPageToken(null);
    verify(filesList, times(1)).setQ("'driveFolderId' in parents and sampleQuery");
    verify(filesList, times(10)).getPageToken();
  }

  @Test
  void testCreateOrUpdateFile_succeedsForNewFile() throws Exception {
    when(files.create(
            eq(
                new File()
                    .setName("name")
                    .setMimeType("video/webm")
                    .setParents(ImmutableList.of("driveFolderId"))),
            argThat(hasByteArrayContent(DATA))))
        .thenReturn(create);
    FileList emptyFileList = new FileList().setFiles(ImmutableList.of()).setNextPageToken(null);
    when(filesList.execute()).thenReturn(emptyFileList);
    assertThat(
            driveConnection.createOrUpdateFile("name", MediaType.WEBM_VIDEO, "driveFolderId", DATA))
        .isEqualTo("id");
  }

  @Test
  void testCreateOrUpdateFile_succeedsForUpdatingFile() throws Exception {
    when(files.update(eq("id"), eq(new File().setName("name")), argThat(hasByteArrayContent(DATA))))
        .thenReturn(update);
    FileList fileList =
        new FileList().setFiles(ImmutableList.of(new File().setId("id"))).setNextPageToken(null);
    when(filesList.execute()).thenReturn(fileList);
    assertThat(
            driveConnection.createOrUpdateFile("name", MediaType.WEBM_VIDEO, "driveFolderId", DATA))
        .isEqualTo("id");
  }

  @Test
  void testCreateOrUpdateFile_throwsExceptionWhenMultipleFilesWithNameAlreadyExist()
      throws Exception {
    FileList fileList =
        new FileList()
            .setFiles(ImmutableList.of(new File().setId("id1"), new File().setId("id2")))
            .setNextPageToken(null);
    when(filesList.execute()).thenReturn(fileList);
    IllegalStateException thrown =
        assertThrows(
            IllegalStateException.class,
            () ->
                driveConnection.createOrUpdateFile(
                    "name", MediaType.WEBM_VIDEO, "driveFolderId", DATA));
    assertThat(thrown)
        .hasMessageThat()
        .contains(
            "Could not update file 'name' in Drive folder id 'driveFolderId' "
                + "because multiple files with that name already exist.");
  }

  @Test
  void testUpdateFile_succeeds() throws Exception {
    when(files.update(eq("id"), eq(new File().setName("name")), argThat(hasByteArrayContent(DATA))))
        .thenReturn(update);
    assertThat(driveConnection.updateFile("id", "name", MediaType.WEBM_VIDEO, DATA))
        .isEqualTo("id");
  }
}
