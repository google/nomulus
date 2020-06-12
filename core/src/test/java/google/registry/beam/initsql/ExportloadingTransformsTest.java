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

import static google.registry.model.ofy.ObjectifyService.ofy;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.testing.DatastoreHelper.newContactResource;
import static google.registry.testing.DatastoreHelper.newDomainBase;
import static google.registry.testing.DatastoreHelper.newRegistry;

import com.google.appengine.api.datastore.Entity;
import com.google.common.collect.ImmutableList;
import com.googlecode.objectify.Key;
import google.registry.backup.VersionedEntity;
import google.registry.model.contact.ContactResource;
import google.registry.model.domain.DomainBase;
import google.registry.model.ofy.Ofy;
import google.registry.model.registry.Registry;
import google.registry.testing.FakeClock;
import google.registry.testing.InjectRule;
import java.io.File;
import java.io.Serializable;
import java.util.Collections;
import org.apache.beam.sdk.coders.StringUtf8Coder;
import org.apache.beam.sdk.io.fs.MatchResult.Metadata;
import org.apache.beam.sdk.testing.NeedsRunner;
import org.apache.beam.sdk.testing.PAssert;
import org.apache.beam.sdk.testing.TestPipeline;
import org.apache.beam.sdk.transforms.Create;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.ParDo;
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

/**
 * Unit tests for {@link ExportLoadingTransforms}.
 *
 * <p>This class implements {@link Serializable} so that test {@link DoFn} classes may be inlined.
 */
// TODO(weiminyu): Upgrade to JUnit5 when TestPipeline is upgraded. It is also easy to adapt with
// a wrapper.
@RunWith(JUnit4.class)
public class ExportloadingTransformsTest implements Serializable {
  private static final DateTime START_TIME = DateTime.parse("2000-01-01T00:00:00.0Z");

  private static final ImmutableList<Class<?>> ALL_KINDS =
      ImmutableList.of(Registry.class, ContactResource.class, DomainBase.class);
  private static final ImmutableList<String> ALL_KIND_STRS =
      ALL_KINDS.stream().map(Key::getKind).collect(ImmutableList.toImmutableList());

  @Rule public final transient TemporaryFolder exportRootDir = new TemporaryFolder();

  @Rule public final transient InjectRule injectRule = new InjectRule();

  @Rule
  public final transient TestPipeline pipeline =
      TestPipeline.create().enableAbandonedNodeEnforcement(true);

  private FakeClock fakeClock;
  private transient BackupTestStore store;
  private File exportDir;
  // Canned data that are persisted to Datastore, used by assertions in tests.
  // TODO(weiminyu): use Ofy entity pojos directly.
  private transient ImmutableList<Entity> persistedEntities;

  @Before
  public void beforeEach() throws Exception {
    fakeClock = new FakeClock(START_TIME);
    store = new BackupTestStore(fakeClock);
    injectRule.setStaticField(Ofy.class, "clock", fakeClock);

    Registry registry = newRegistry("tld1", "TLD1");
    store.insertOrUpdate(registry);
    ContactResource contact1 = newContactResource("contact_1");
    DomainBase domain1 = newDomainBase("domain1.tld1", contact1);
    store.insertOrUpdate(contact1, domain1);
    persistedEntities =
        ImmutableList.of(registry, contact1, domain1).stream()
            .map(ofyEntity -> tm().transact(() -> ofy().save().toEntity(ofyEntity)))
            .collect(ImmutableList.toImmutableList());

    exportDir =
        store.export(exportRootDir.getRoot().getAbsolutePath(), ALL_KINDS, Collections.EMPTY_SET);
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
  public void getExportFilePatterns() {
    PCollection<String> patterns =
        pipeline.apply(
            "Get Datastore file patterns",
            ExportLoadingTransforms.getDatastoreExportFilePatterns(
                exportDir.getAbsolutePath(), ALL_KIND_STRS));

    ImmutableList<String> expectedPatterns =
        ImmutableList.of(
            exportDir.getAbsolutePath() + "/all_namespaces/kind_Registry/input-*",
            exportDir.getAbsolutePath() + "/all_namespaces/kind_DomainBase/input-*",
            exportDir.getAbsolutePath() + "/all_namespaces/kind_ContactResource/input-*");

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
                Create.of(
                        exportDir.getAbsolutePath() + "/all_namespaces/kind_Registry/input-*",
                        exportDir.getAbsolutePath() + "/all_namespaces/kind_DomainBase/input-*",
                        exportDir.getAbsolutePath()
                            + "/all_namespaces/kind_ContactResource/input-*")
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
        ImmutableList.of(
            exportDir.getAbsolutePath() + "/all_namespaces/kind_Registry/input-0",
            exportDir.getAbsolutePath() + "/all_namespaces/kind_DomainBase/input-0",
            exportDir.getAbsolutePath() + "/all_namespaces/kind_ContactResource/input-0");

    PAssert.that(fileNames).containsInAnyOrder(expectedFilenames);

    pipeline.run();
  }

  @Test
  public void loadDataFromFiles() {
    PCollection<VersionedEntity> taggedRecords =
        pipeline
            .apply(
                "Get Datastore file patterns",
                ExportLoadingTransforms.getDatastoreExportFilePatterns(
                    exportDir.getAbsolutePath(), ALL_KIND_STRS))
            .apply("Find Datastore files", Transforms.getFilesByPatterns())
            .apply("Load from Datastore files", ExportLoadingTransforms.loadExportDataFromFiles());

    // Transform bytes to pojo for analysis
    PCollection<Entity> entities =
        taggedRecords.apply(
            "Raw records to Entity",
            ParDo.of(
                new DoFn<VersionedEntity, Entity>() {
                  @ProcessElement
                  public void processElement(
                      @Element VersionedEntity versionedEntity, OutputReceiver<Entity> out) {
                    out.output(versionedEntity.getEntity().get());
                  }
                }));

    PAssert.that(entities).containsInAnyOrder(persistedEntities);

    pipeline.run();
  }
}
