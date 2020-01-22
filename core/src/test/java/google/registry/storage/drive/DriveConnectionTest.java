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

import static com.google.common.io.ByteStreams.toByteArray;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.api.client.http.ByteArrayContent;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.Drive.Children;
import com.google.api.services.drive.Drive.Files;
import com.google.api.services.drive.model.ChildList;
import com.google.api.services.drive.model.ChildReference;
import com.google.api.services.drive.model.File;
import com.google.api.services.drive.model.ParentReference;
import com.google.common.collect.ImmutableList;
import com.google.common.net.MediaType;
import java.util.Arrays;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentMatcher;

/** Tests for {@link DriveConnection}.*/
@RunWith(JUnit4.class)
public class DriveConnectionTest {
  private final Drive drive = mock(Drive.class);
  private final Files files = mock(Files.class);
  private final Children children = mock(Children.class);
  private final Files.Insert insert = mock(Files.Insert.class);
  private final Files.Update update = mock(Files.Update.class);
  private final Children.List childrenList = mock(Children.List.class);

  private static final byte[] DATA = {1, 2, 3};
  ChildList childList;
  DriveConnection driveConnection;
  List<String> allChildren;

  private ArgumentMatcher<ByteArrayContent> hasByteArrayContent(final byte[] data) {
    return arg -> {
      try {
        return Arrays.equals(data, toByteArray(arg.getInputStream()));
      } catch (Exception e) {
        return false;
      }
    };
  }

  @Before
  public void init() throws Exception {
    driveConnection = new DriveConnection();
    driveConnection.drive = drive;
    when(drive.files()).thenReturn(files);
    when(drive.children()).thenReturn(children);
    when(insert.execute()).thenReturn(new File().setId("id"));
    when(update.execute()).thenReturn(new File().setId("id"));

    // Mocking required for listFiles.
    ChildReference child1 = new ChildReference().setId("child1");
    ChildReference child2 = new ChildReference().setId("child2");
    ChildReference child3 = new ChildReference().setId("child3");
    ChildReference child4 = new ChildReference().setId("child4");
    List<ChildReference> children1 = ImmutableList.of(child1, child2);
    List<ChildReference> children2 = ImmutableList.of(child3, child4);
    allChildren = ImmutableList.of(child1.getId(), child2.getId(), child3.getId(), child4.getId());
    ChildList childList1 = new ChildList();
    childList1.setItems(children1);
    childList1.setNextPageToken("page2");
    ChildList childList2 = new ChildList();
    childList2.setItems(children2);
    childList2.setNextPageToken(null);
    when(childrenList.execute()).thenReturn(childList1, childList2);
    when(childrenList.setQ(anyString())).thenReturn(childrenList);
    when(childrenList.getPageToken()).thenCallRealMethod();
    when(childrenList.setPageToken(any())).thenCallRealMethod();
    when(children.list("driveFolderId")).thenReturn(childrenList);
  }

  @Test
  public void testCreateFileAtRoot() throws Exception {
    when(files.insert(
            eq(new File().setTitle("title").setMimeType("image/gif")),
            argThat(hasByteArrayContent(DATA))))
        .thenReturn(insert);
    assertThat(driveConnection.createFile("title", MediaType.GIF, null, DATA)).isEqualTo("id");
  }

  @Test
  public void testCreateFileInFolder() throws Exception {
    when(files.insert(
            eq(
                new File()
                    .setTitle("title")
                    .setMimeType("image/gif")
                    .setParents(ImmutableList.of(new ParentReference().setId("parent")))),
            argThat(hasByteArrayContent(DATA))))
        .thenReturn(insert);
    assertThat(driveConnection.createFile("title", MediaType.GIF, "parent", DATA)).isEqualTo("id");
  }

  @Test
  public void testCreateFolderAtRoot() throws Exception {
    when(files.insert(new File()
        .setTitle("title")
        .setMimeType("application/vnd.google-apps.folder")))
            .thenReturn(insert);
    assertThat(driveConnection.createFolder("title", null)).isEqualTo("id");
  }

  @Test
  public void testCreateFolderInFolder() throws Exception {
    when(files.insert(new File()
        .setTitle("title")
        .setMimeType("application/vnd.google-apps.folder")
        .setParents(ImmutableList.of(new ParentReference().setId("parent")))))
            .thenReturn(insert);
    assertThat(driveConnection.createFolder("title", "parent")).isEqualTo("id");
  }

  @Test
  public void testListFiles_noQueryWithPagination() throws Exception {
    assertThat(driveConnection.listFiles("driveFolderId"))
        .containsExactlyElementsIn(allChildren);
    verify(childrenList).setPageToken("page2");
    verify(childrenList).setPageToken(null);
    verify(childrenList, times(0)).setQ(anyString());
    verify(childrenList, times(2)).getPageToken();
  }

  @Test
  public void testListFiles_withQueryAndPagination() throws Exception {
    assertThat(driveConnection.listFiles("driveFolderId", "sampleQuery"))
        .containsExactlyElementsIn(allChildren);
    verify(childrenList).setPageToken("page2");
    verify(childrenList).setPageToken(null);
    verify(childrenList, times(1)).setQ("sampleQuery");
    verify(childrenList, times(2)).getPageToken();
  }

  @Test
  public void testCreateOrUpdateFile_succeedsForNewFile() throws Exception {
    when(files.insert(
            eq(
                new File()
                    .setTitle("title")
                    .setMimeType("video/webm")
                    .setParents(ImmutableList.of(new ParentReference().setId("driveFolderId")))),
            argThat(hasByteArrayContent(DATA))))
        .thenReturn(insert);
    ChildList emptyChildList = new ChildList().setItems(ImmutableList.of()).setNextPageToken(null);
    when(childrenList.execute()).thenReturn(emptyChildList);
    assertThat(driveConnection.createOrUpdateFile(
            "title",
            MediaType.WEBM_VIDEO,
            "driveFolderId",
            DATA))
        .isEqualTo("id");
  }

  @Test
  public void testCreateOrUpdateFile_succeedsForUpdatingFile() throws Exception {
    when(files.update(
            eq("id"), eq(new File().setTitle("title")), argThat(hasByteArrayContent(DATA))))
        .thenReturn(update);
    ChildList childList = new ChildList()
        .setItems(ImmutableList.of(new ChildReference().setId("id")))
        .setNextPageToken(null);
    when(childrenList.execute()).thenReturn(childList);
    assertThat(driveConnection.createOrUpdateFile(
            "title",
            MediaType.WEBM_VIDEO,
            "driveFolderId",
            DATA))
        .isEqualTo("id");
  }

  @Test
  public void testCreateOrUpdateFile_throwsExceptionWhenMultipleFilesWithNameAlreadyExist()
      throws Exception {
    ChildList childList = new ChildList()
      .setItems(ImmutableList.of(
          new ChildReference().setId("id1"),
          new ChildReference().setId("id2")))
      .setNextPageToken(null);
    when(childrenList.execute()).thenReturn(childList);
    IllegalStateException thrown =
        assertThrows(
            IllegalStateException.class,
            () ->
                driveConnection.createOrUpdateFile(
                    "title", MediaType.WEBM_VIDEO, "driveFolderId", DATA));
    assertThat(thrown)
        .hasMessageThat()
        .contains(
            "Could not update file 'title' in Drive folder id 'driveFolderId' "
                + "because multiple files with that name already exist.");
  }

  @Test
  public void testUpdateFile_succeeds() throws Exception {
    when(files.update(
            eq("id"), eq(new File().setTitle("title")), argThat(hasByteArrayContent(DATA))))
        .thenReturn(update);
    assertThat(driveConnection.updateFile("id", "title", MediaType.WEBM_VIDEO, DATA))
        .isEqualTo("id");
  }
}
