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

import static google.registry.testing.DatabaseHelper.newContactResource;
import static google.registry.testing.DatabaseHelper.newDomainBase;
import static google.registry.testing.DatabaseHelper.newRegistry;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.googlecode.objectify.Key;
import google.registry.backup.VersionedEntity;
import google.registry.beam.TestPipelineExtension;
import google.registry.model.contact.ContactResource;
import google.registry.model.domain.DomainBase;
import google.registry.model.ofy.Ofy;
import google.registry.model.registry.Registry;
import google.registry.persistence.transaction.JpaTestRules;
import google.registry.persistence.transaction.JpaTestRules.JpaIntegrationTestExtension;
import google.registry.testing.DatastoreEntityExtension;
import google.registry.testing.FakeClock;
import google.registry.testing.InjectExtension;
import java.io.File;
import java.io.Serializable;
import java.nio.file.Path;
import java.util.Collections;
import org.apache.beam.sdk.coders.StringUtf8Coder;
import org.apache.beam.sdk.io.fs.MatchResult.Metadata;
import org.apache.beam.sdk.testing.PAssert;
import org.apache.beam.sdk.transforms.Create;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.beam.sdk.values.KV;
import org.apache.beam.sdk.values.PCollection;
import org.joda.time.DateTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for {@link Transforms} related to loading Datastore exports.
 *
 * <p>This class implements {@link Serializable} so that test {@link DoFn} classes may be inlined.
 */
class ExportLoadingTransformsTest implements Serializable {

  private static final DateTime START_TIME = DateTime.parse("2000-01-01T00:00:00.0Z");

  private static final ImmutableList<Class<?>> ALL_KINDS =
      ImmutableList.of(Registry.class, ContactResource.class, DomainBase.class);
  private static final ImmutableSet<String> ALL_KIND_STRS =
      ALL_KINDS.stream().map(Key::getKind).collect(ImmutableSet.toImmutableSet());

  @SuppressWarnings("WeakerAccess")
  @TempDir
  transient Path tmpDir;

  @RegisterExtension final transient InjectExtension injectRule = new InjectExtension();

  @RegisterExtension
  final transient JpaIntegrationTestExtension jpaIntegrationTestExtension =
      new JpaTestRules.Builder().buildIntegrationTestRule();

  @RegisterExtension
  @Order(value = 1)
  final transient DatastoreEntityExtension datastoreEntityExtension =
      new DatastoreEntityExtension();

  @RegisterExtension
  final transient TestPipelineExtension testPipeline =
      TestPipelineExtension.create().enableAbandonedNodeEnforcement(true);

  private FakeClock fakeClock;
  private transient BackupTestStore store;
  private File exportDir;

  // Canned data:
  private transient Registry registry;
  private transient ContactResource contact;
  private transient DomainBase domain;

  @BeforeEach
  void beforeEach() throws Exception {
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

    exportDir = store.export(tmpDir.toAbsolutePath().toString(), ALL_KINDS, Collections.EMPTY_SET);
  }

  @AfterEach
  void afterEach() throws Exception {
    if (store != null) {
      store.close();
      store = null;
    }
  }

  @Test
  void getExportFilePatterns() {
    PCollection<String> patterns =
        testPipeline.apply(
            "Get Datastore file patterns",
            Transforms.getDatastoreExportFilePatterns(exportDir.getAbsolutePath(), ALL_KIND_STRS));

    ImmutableList<String> expectedPatterns =
        ImmutableList.of(
            exportDir.getAbsolutePath() + "/all_namespaces/kind_Registry/output-*",
            exportDir.getAbsolutePath() + "/all_namespaces/kind_DomainBase/output-*",
            exportDir.getAbsolutePath() + "/all_namespaces/kind_ContactResource/output-*");

    PAssert.that(patterns).containsInAnyOrder(expectedPatterns);

    testPipeline.run();
  }

  @Test
  void getFilesByPatterns() {
    PCollection<Metadata> fileMetas =
        testPipeline
            .apply(
                "File patterns to metadata",
                Create.of(
                        exportDir.getAbsolutePath() + "/all_namespaces/kind_Registry/output-*",
                        exportDir.getAbsolutePath() + "/all_namespaces/kind_DomainBase/output-*",
                        exportDir.getAbsolutePath()
                            + "/all_namespaces/kind_ContactResource/output-*")
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
            exportDir.getAbsolutePath() + "/all_namespaces/kind_Registry/output-0",
            exportDir.getAbsolutePath() + "/all_namespaces/kind_DomainBase/output-0",
            exportDir.getAbsolutePath() + "/all_namespaces/kind_ContactResource/output-0");

    PAssert.that(fileNames).containsInAnyOrder(expectedFilenames);

    testPipeline.run();
  }

  @Test
  void loadDataFromFiles() {
    PCollection<VersionedEntity> entities =
        testPipeline
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

    testPipeline.run();
  }
}
