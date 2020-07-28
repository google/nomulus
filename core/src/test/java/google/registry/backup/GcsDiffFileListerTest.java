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
import static com.google.common.util.concurrent.MoreExecutors.newDirectExecutorService;
import static google.registry.backup.BackupUtils.GcsMetadataKeys.LOWER_BOUND_CHECKPOINT;
import static google.registry.backup.ExportCommitLogDiffAction.DIFF_FILE_PREFIX;
import static java.lang.reflect.Proxy.newProxyInstance;
import static org.joda.time.DateTimeZone.UTC;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.fail;

import com.google.appengine.tools.cloudstorage.GcsFileMetadata;
import com.google.appengine.tools.cloudstorage.GcsFileOptions;
import com.google.appengine.tools.cloudstorage.GcsFilename;
import com.google.appengine.tools.cloudstorage.GcsService;
import com.google.appengine.tools.cloudstorage.GcsServiceFactory;
import com.google.appengine.tools.cloudstorage.ListItem;
import com.google.appengine.tools.cloudstorage.ListResult;
import com.google.common.collect.Iterators;
import com.google.common.flogger.LoggerConfig;
import com.google.common.testing.TestLogHandler;
import google.registry.testing.AppEngineExtension;
import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.Callable;
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
  private final GcsService gcsService = GcsServiceFactory.createGcsService();
  private final TestLogHandler logHandler = new TestLogHandler();

  @RegisterExtension
  public final AppEngineExtension appEngine =
      AppEngineExtension.builder().withDatastoreAndCloudSql().build();

  @BeforeEach
  void beforeEach() throws Exception {
    diffLister.gcsService = gcsService;
    diffLister.gcsBucket = GCS_BUCKET;
    diffLister.executor = newDirectExecutorService();
    for (int i = 0; i < 5; i++) {
      gcsService.createOrReplace(
          new GcsFilename(GCS_BUCKET, DIFF_FILE_PREFIX + now.minusMinutes(i)),
          new GcsFileOptions.Builder()
              .addUserMetadata(LOWER_BOUND_CHECKPOINT, now.minusMinutes(i + 1).toString())
              .build(),
          ByteBuffer.wrap(new byte[]{1, 2, 3}));
    }
    LoggerConfig.getConfig(GcsDiffFileLister.class).addHandler(logHandler);
  }

  private Iterable<DateTime> extractTimesFromDiffFiles(List<GcsFileMetadata> diffFiles) {
    return transform(
        diffFiles,
        metadata ->
            DateTime.parse(
                metadata.getFilename().getObjectName().substring(DIFF_FILE_PREFIX.length())));
  }

  private Iterable<DateTime> listDiffFiles(DateTime fromTime, DateTime toTime) {
    return extractTimesFromDiffFiles(diffLister.listDiffFiles(fromTime, toTime));
  }

  private void addGcsFile(int fileAge, int prevAge) throws IOException {
    gcsService.createOrReplace(
        new GcsFilename(GCS_BUCKET, DIFF_FILE_PREFIX + now.minusMinutes(fileAge)),
        new GcsFileOptions.Builder()
            .addUserMetadata(LOWER_BOUND_CHECKPOINT, now.minusMinutes(prevAge).toString())
            .build(),
        ByteBuffer.wrap(new byte[]{1, 2, 3}));
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
  void testList_patchesHoles() {
    // Fake out the GCS list() method to return only the first and last file.
    // We can't use Mockito.spy() because GcsService's impl is final.
    diffLister.gcsService = (GcsService) newProxyInstance(
        GcsService.class.getClassLoader(),
        new Class<?>[] {GcsService.class},
        new InvocationHandler() {
          @Override
          public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (method.getName().equals("list")) {
              // ListResult is an incredibly annoying thing to construct. It needs to be fed from a
              // Callable that returns Iterators, each representing a batch of results.
              return new ListResult(new Callable<Iterator<ListItem>>() {
                boolean called = false;

                @Override
                public Iterator<ListItem> call() {
                  try {
                    return called ? null : Iterators.forArray(
                        new ListItem.Builder()
                            .setName(DIFF_FILE_PREFIX + now)
                            .build(),
                        new ListItem.Builder()
                            .setName(DIFF_FILE_PREFIX + now.minusMinutes(4))
                            .build());
                  } finally {
                    called = true;
                  }
                }});
            }
            return method.invoke(gcsService, args);
          }});
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
    assertThat(listDiffFiles(
            now.minusMinutes(4).minusSeconds(1), now.minusMinutes(2).plusSeconds(1)))
        .containsExactly(
            now.minusMinutes(4),
            now.minusMinutes(3),
            now.minusMinutes(2))
        .inOrder();
  }
}
