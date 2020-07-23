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
import com.googlecode.objectify.Key;
import google.registry.backup.VersionedEntity;
import google.registry.beam.TestPipelineExtension;
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

/**
 * Unit tests for {@link Transforms} related to loading Datastore exports.
 *
 * <p>This class implements {@link Serializable} so that test {@link DoFn} classes may be inlined.
 */
@RunWith(JUnit4.class)
public class ExportloadingTransformsTest implements Serializable {
  private static final DateTime START_TIME = DateTime.parse("2000-01-01T00:00:00.0Z");

  private static final ImmutableList<Class<?>> ALL_KINDS =
      ImmutableList.of(Registry.class, ContactResource.class, DomainBase.class);
  private static final ImmutableSet<String> ALL_KIND_STRS =
      ALL_KINDS.stream().map(Key::getKind).collect(ImmutableSet.toImmutableSet());

  @Rule public final transient TemporaryFolder exportRootDir = new TemporaryFolder();

  @Rule public final transient InjectRule injectRule = new InjectRule();

  @Rule
  public final transient TestPipelineExtension pipeline =
      TestPipelineExtension.create().enableAbandonedNodeEnforcement(true);

  private FakeClock fakeClock;
  private transient BackupTestStore store;
  private File exportDir;

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
            Transforms.getDatastoreExportFilePatterns(exportDir.getAbsolutePath(), ALL_KIND_STRS));

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
  @Category(NeedsRunner.class)
  public void loadDataFromFiles() {
    PCollection<VersionedEntity> entities =
        pipeline
            .apply(
                "Get Datastore file patterns",
                Transforms.getDatastoreExportFilePatterns(
                    exportDir.getAbsolutePath(), ALL_KIND_STRS))
            .apply("Find Datastore files", Transforms.getFilesByPatterns())
            .apply("Load from Datastore files", Transforms.loadExportDataFromFiles());

    InitSqlTestUtils.assertContainsExactlyElementsIn(
        entities,
        KV.of(Transforms.EXPORT_ENTITY_TIME_STAMP, store.loadAsDatastoreEntity(registry)),
        KV.of(Transforms.EXPORT_ENTITY_TIME_STAMP, store.loadAsDatastoreEntity(contact)),
        KV.of(Transforms.EXPORT_ENTITY_TIME_STAMP, store.loadAsDatastoreEntity(domain)));

    pipeline.run();
  }
}
