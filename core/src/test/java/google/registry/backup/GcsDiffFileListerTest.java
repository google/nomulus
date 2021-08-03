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

package google.registry.backup;

import static com.google.common.collect.Iterables.transform;
import static com.google.common.truth.Truth.assertThat;
import static google.registry.backup.BackupUtils.GcsMetadataKeys.LOWER_BOUND_CHECKPOINT;
import static google.registry.backup.ExportCommitLogDiffAction.DIFF_FILE_PREFIX;
import static google.registry.backup.GcsDiffFileLister.getCommitLogDiffPrefix;
import static org.joda.time.DateTimeZone.UTC;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.contrib.nio.testing.LocalStorageHelper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.flogger.LoggerConfig;
import com.google.common.testing.TestLogHandler;
import com.google.common.util.concurrent.MoreExecutors;
import google.registry.gcs.GcsUtils;
import google.registry.testing.AppEngineExtension;
import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.logging.LogRecord;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link GcsDiffFileLister}. */
public class GcsDiffFileListerTest {

  private static final String GCS_BUCKET = "gcs bucket";

  private final DateTime now = DateTime.now(UTC);
  private final GcsDiffFileLister diffLister = new GcsDiffFileLister();
  private final GcsUtils gcsUtils = new GcsUtils(LocalStorageHelper.getOptions());
  private final TestLogHandler logHandler = new TestLogHandler();

  @RegisterExtension
  public final AppEngineExtension appEngine =
      AppEngineExtension.builder().withDatastoreAndCloudSql().build();

  @BeforeEach
  void beforeEach() throws Exception {
    diffLister.gcsUtils = gcsUtils;
    diffLister.executorProvider = MoreExecutors::newDirectExecutorService;
    diffLister.scheduledExecutorService = Executors.newSingleThreadScheduledExecutor();
    for (int i = 0; i < 5; i++) {
      addGcsFile(i, i + 1);
    }
    LoggerConfig.getConfig(GcsDiffFileLister.class).addHandler(logHandler);
  }

  private Iterable<DateTime> extractTimesFromDiffFiles(ImmutableList<BlobInfo> diffFiles) {
    return transform(
        diffFiles,
        blobInfo -> DateTime.parse(blobInfo.getName().substring(DIFF_FILE_PREFIX.length())));
  }

  private Iterable<DateTime> listDiffFiles(DateTime fromTime, DateTime toTime) {
    return extractTimesFromDiffFiles(diffLister.listDiffFiles(GCS_BUCKET, fromTime, toTime));
  }

  private void addGcsFile(int fileAge, int prevAge) throws IOException {
    BlobInfo blobInfo =
        BlobInfo.newBuilder(BlobId.of(GCS_BUCKET, DIFF_FILE_PREFIX + now.minusMinutes(fileAge)))
            .setMetadata(
                ImmutableMap.of(LOWER_BOUND_CHECKPOINT, now.minusMinutes(prevAge).toString()))
            .build();
    gcsUtils.createFromBytes(blobInfo, new byte[] {1, 2, 3});
  }

  private void assertLogContains(String message) {
    for (LogRecord entry : logHandler.getStoredLogRecords()) {
      if (entry.getMessage().contains(message)) {
        return;
      }
    }
    fail("No log entry contains " + message);
  }

  @Test
  void testList_noFilesFound() {
    DateTime fromTime = now.plusMillis(1);
    assertThat(listDiffFiles(fromTime, null)).isEmpty();
  }

  @Test
  void testList_patchesHoles() throws Exception {
    GcsUtils mockGcsUtils = mock(GcsUtils.class);
    diffLister.gcsUtils = spy(gcsUtils);
    when(mockGcsUtils.listFolderObjects(anyString(), anyString()))
        .thenReturn(ImmutableList.of(now.toString(), now.minusMinutes(4).toString()));
    DateTime fromTime = now.minusMinutes(4).minusSeconds(1);
    // Request all files with checkpoint > fromTime.
    assertThat(listDiffFiles(fromTime, null))
        .containsExactly(
            now.minusMinutes(4),
            now.minusMinutes(3),
            now.minusMinutes(2),
            now.minusMinutes(1),
            now)
        .inOrder();
  }

  @Test
  void testList_failsOnFork() throws Exception {
    // We currently have files for now-4m ... now, construct the following sequence:
    //  now-8m <- now-7m <- now-6m  now-5m <- now-4m ... now
    //    ^___________________________|
    addGcsFile(5, 8);
    for (int i = 6; i < 9; ++i) {
      addGcsFile(i, i + 1);
    }

    assertThrows(IllegalStateException.class, () -> listDiffFiles(now.minusMinutes(9), null));
    assertLogContains(String.format(
        "Found sequence from %s to %s", now.minusMinutes(9), now));
    assertLogContains(String.format(
        "Found sequence from %s to %s", now.minusMinutes(9), now.minusMinutes(6)));
  }

  @Test
  void testList_boundaries() {
    assertThat(listDiffFiles(now.minusMinutes(4), now))
        .containsExactly(
            now.minusMinutes(4),
            now.minusMinutes(3),
            now.minusMinutes(2),
            now.minusMinutes(1),
            now)
        .inOrder();

  }

  @Test
  void testList_failsOnGaps() throws Exception {
    // We currently have files for now-4m ... now, construct the following sequence:
    //  now-8m <- now-7m <- now-6m  {missing} <- now-4m ... now
    for (int i = 6; i < 9; ++i) {
      addGcsFile(i, i + 1);
    }

    assertThrows(IllegalStateException.class, () -> listDiffFiles(now.minusMinutes(9), null));
    assertLogContains(String.format(
        "Gap discovered in sequence terminating at %s, missing file: commit_diff_until_%s",
        now, now.minusMinutes(5)));
    assertLogContains(String.format(
        "Found sequence from %s to %s", now.minusMinutes(9), now.minusMinutes(6)));
    assertLogContains(String.format(
        "Found sequence from %s to %s", now.minusMinutes(5), now));

    // Verify that we can work around the gap.
    DateTime fromTime = now.minusMinutes(4).minusSeconds(1);
    assertThat(listDiffFiles(fromTime, null))
        .containsExactly(
            now.minusMinutes(4),
            now.minusMinutes(3),
            now.minusMinutes(2),
            now.minusMinutes(1),
            now)
        .inOrder();
    assertThat(listDiffFiles(
            now.minusMinutes(8).minusSeconds(1), now.minusMinutes(6).plusSeconds(1)))
        .containsExactly(
            now.minusMinutes(8),
            now.minusMinutes(7),
            now.minusMinutes(6))
        .inOrder();
  }

  @Test
  void testList_toTimeSpecified() {
    assertThat(
            listDiffFiles(now.minusMinutes(4).minusSeconds(1), now.minusMinutes(2).plusSeconds(1)))
        .containsExactly(now.minusMinutes(4), now.minusMinutes(3), now.minusMinutes(2))
        .inOrder();
  }

  @Test
  void testPrefix_lengthened() {
    DateTime from = DateTime.parse("2021-05-11T06:48:00.070Z");
    assertThat(getCommitLogDiffPrefix(from, null)).isEqualTo("commit_diff_until_");
    assertThat(getCommitLogDiffPrefix(from, DateTime.parse("2021-07-01")))
        .isEqualTo("commit_diff_until_2021-");
    assertThat(getCommitLogDiffPrefix(from, DateTime.parse("2021-05-21")))
        .isEqualTo("commit_diff_until_2021-05-");
    assertThat(getCommitLogDiffPrefix(from, DateTime.parse("2021-05-11T09:48:00.070Z")))
        .isEqualTo("commit_diff_until_2021-05-11T");
    assertThat(getCommitLogDiffPrefix(from, DateTime.parse("2021-05-11T06:59:00.070Z")))
        .isEqualTo("commit_diff_until_2021-05-11T06:");
  }
}
