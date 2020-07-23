// Copyright 2020 The Nomulus Authors. All Rights Reserved.
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

package google.registry.beam.initsql;

import static google.registry.testing.DatastoreHelper.newContactResource;
import static google.registry.testing.DatastoreHelper.newDomainBase;
import static google.registry.testing.DatastoreHelper.newRegistry;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import google.registry.backup.VersionedEntity;
import google.registry.beam.TestPipelineExtension;
import google.registry.model.contact.ContactResource;
import google.registry.model.domain.DomainBase;
import google.registry.model.ofy.Ofy;
import google.registry.model.registry.Registry;
import google.registry.testing.FakeClock;
import google.registry.testing.InjectRule;
import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import org.apache.beam.sdk.coders.StringUtf8Coder;
import org.apache.beam.sdk.io.fs.MatchResult.Metadata;
import org.apache.beam.sdk.testing.NeedsRunner;
import org.apache.beam.sdk.testing.PAssert;
import org.apache.beam.sdk.transforms.Create;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.beam.sdk.values.KV;
import org.apache.beam.sdk.values.PCollection;
import org.joda.time.DateTime;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link Transforms} related to loading CommitLogs. */
@RunWith(JUnit4.class)
public class CommitLogTransformsTest implements Serializable {

  private static final DateTime START_TIME = DateTime.parse("2000-01-01T00:00:00.0Z");

  @Rule public final transient TemporaryFolder temporaryFolder = new TemporaryFolder();

  @Rule public final transient InjectRule injectRule = new InjectRule();

  @Rule
  public final transient TestPipelineExtension pipeline =
      TestPipelineExtension.create().enableAbandonedNodeEnforcement(true);

  private FakeClock fakeClock;
  private transient BackupTestStore store;
  private File commitLogsDir;
  private File firstCommitLogFile;

  // Canned data:
  private transient Registry registry;
  private transient ContactResource contact;
  private transient DomainBase domain;

  @Before
  public void beforeEach() throws Exception {
    fakeClock = new FakeClock(START_TIME);
    store = new BackupTestStore(fakeClock);
    injectRule.setStaticField(Ofy.class, "clock", fakeClock);

    registry = newRegistry("tld1", "TLD1");
    store.insertOrUpdate(registry);
    contact = newContactResource("contact_1");
    domain = newDomainBase("domain1.tld1", contact);
    store.insertOrUpdate(contact, domain);

    // Save persisted data for assertions.
    registry = (Registry) store.loadAsOfyEntity(registry);
    contact = (ContactResource) store.loadAsOfyEntity(contact);
    domain = (DomainBase) store.loadAsOfyEntity(domain);

    commitLogsDir = temporaryFolder.newFolder();
    firstCommitLogFile = store.saveCommitLogs(commitLogsDir.getAbsolutePath());
  }

  @After
  public void afterEach() throws Exception {
    if (store != null) {
      store.close();
      store = null;
    }
  }

  @Test
  @Category(NeedsRunner.class)
  public void getCommitLogFilePatterns() {
    PCollection<String> patterns =
        pipeline.apply(
            "Get CommitLog file patterns",
            Transforms.getCommitLogFilePatterns(commitLogsDir.getAbsolutePath()));

    ImmutableList<String> expectedPatterns =
        ImmutableList.of(commitLogsDir.getAbsolutePath() + "/commit_diff_until_*");

    PAssert.that(patterns).containsInAnyOrder(expectedPatterns);

    pipeline.run();
  }

  @Test
  @Category(NeedsRunner.class)
  public void getFilesByPatterns() {
    PCollection<Metadata> fileMetas =
        pipeline
            .apply(
                "File patterns to metadata",
                Create.of(commitLogsDir.getAbsolutePath() + "/commit_diff_until_*")
                    .withCoder(StringUtf8Coder.of()))
            .apply(Transforms.getFilesByPatterns());

    // Transform fileMetas to file names for assertions.
    PCollection<String> fileNames =
        fileMetas.apply(
            "File metadata to path string",
            ParDo.of(
                new DoFn<Metadata, String>() {
                  @ProcessElement
                  public void processElement(
                      @Element Metadata metadata, OutputReceiver<String> out) {
                    out.output(metadata.resourceId().toString());
                  }
                }));

    ImmutableList<String> expectedFilenames =
        ImmutableList.of(firstCommitLogFile.getAbsolutePath());

    PAssert.that(fileNames).containsInAnyOrder(expectedFilenames);

    pipeline.run();
  }

  @Test
  @Category(NeedsRunner.class)
  public void filterCommitLogsByTime() throws IOException {
    ImmutableList<String> commitLogFilenames =
        ImmutableList.of(
            "commit_diff_until_2000-01-01T00:00:00.000Z",
            "commit_diff_until_2000-01-01T00:00:00.001Z",
            "commit_diff_until_2000-01-01T00:00:00.002Z",
            "commit_diff_until_2000-01-01T00:00:00.003Z",
            "commit_diff_until_2000-01-01T00:00:00.004Z");

    File commitLogDir = temporaryFolder.newFolder();
    for (String name : commitLogFilenames) {
      new File(commitLogDir, name).createNewFile();
    }

    PCollection<String> filteredFilenames =
        pipeline
            .apply(
                "Get commitlog file patterns",
                Transforms.getCommitLogFilePatterns(commitLogDir.getAbsolutePath()))
            .apply("Find commitlog files", Transforms.getFilesByPatterns())
            .apply(
                "Filtered by Time",
                Transforms.filterCommitLogsByTime(
                    DateTime.parse("2000-01-01T00:00:00.001Z"),
                    DateTime.parse("2000-01-01T00:00:00.003Z")))
            .apply(
                "Extract path strings",
                ParDo.of(
                    new DoFn<Metadata, String>() {
                      @ProcessElement
                      public void processElement(
                          @Element Metadata fileMeta, OutputReceiver<String> out) {
                        out.output(fileMeta.resourceId().getFilename());
                      }
                    }));
    PAssert.that(filteredFilenames)
        .containsInAnyOrder(
            "commit_diff_until_2000-01-01T00:00:00.001Z",
            "commit_diff_until_2000-01-01T00:00:00.002Z");

    pipeline.run();
  }

  @Test
  @Category(NeedsRunner.class)
  public void loadOneCommitLogFile() {
    PCollection<VersionedEntity> entities =
        pipeline
            .apply(
                "Get CommitLog file patterns",
                Transforms.getCommitLogFilePatterns(commitLogsDir.getAbsolutePath()))
            .apply("Find CommitLogs", Transforms.getFilesByPatterns())
            .apply(
                Transforms.loadCommitLogsFromFiles(
                    ImmutableSet.of("Registry", "ContactResource", "DomainBase")));

    InitSqlTestUtils.assertContainsExactlyElementsIn(
        entities,
        KV.of(fakeClock.nowUtc().getMillis() - 2, store.loadAsDatastoreEntity(registry)),
        KV.of(fakeClock.nowUtc().getMillis() - 1, store.loadAsDatastoreEntity(contact)),
        KV.of(fakeClock.nowUtc().getMillis() - 1, store.loadAsDatastoreEntity(domain)));

    pipeline.run();
  }

  @Test
  @Category(NeedsRunner.class)
  public void loadOneCommitLogFile_filterByKind() {
    PCollection<VersionedEntity> entities =
        pipeline
            .apply(
                "Get CommitLog file patterns",
                Transforms.getCommitLogFilePatterns(commitLogsDir.getAbsolutePath()))
            .apply("Find CommitLogs", Transforms.getFilesByPatterns())
            .apply(
                Transforms.loadCommitLogsFromFiles(ImmutableSet.of("Registry", "ContactResource")));

    InitSqlTestUtils.assertContainsExactlyElementsIn(
        entities,
        KV.of(fakeClock.nowUtc().getMillis() - 2, store.loadAsDatastoreEntity(registry)),
        KV.of(fakeClock.nowUtc().getMillis() - 1, store.loadAsDatastoreEntity(contact)));

    pipeline.run();
  }
}
