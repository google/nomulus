// Copyright 2021 The Nomulus Authors. All Rights Reserved.
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

package google.registry.beam.common;

import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;

import com.google.common.collect.ImmutableList;
import google.registry.beam.TestPipelineExtension;
import google.registry.beam.common.RegistryJpaIO.Read;
import google.registry.model.contact.ContactBase;
import google.registry.model.contact.ContactResource;
import google.registry.model.registrar.Registrar;
import google.registry.persistence.transaction.JpaTestRules;
import google.registry.persistence.transaction.JpaTestRules.JpaIntegrationTestExtension;
import google.registry.persistence.transaction.JpaTransactionManager;
import google.registry.testing.AppEngineExtension;
import google.registry.testing.DatabaseHelper;
import google.registry.testing.DatastoreEntityExtension;
import google.registry.testing.FakeClock;
import google.registry.testing.SystemPropertyExtension;
import org.apache.beam.sdk.coders.KvCoder;
import org.apache.beam.sdk.coders.StringUtf8Coder;
import org.apache.beam.sdk.testing.PAssert;
import org.apache.beam.sdk.transforms.Deduplicate;
import org.apache.beam.sdk.transforms.Values;
import org.apache.beam.sdk.values.KV;
import org.apache.beam.sdk.values.PCollection;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link RegistryJpaIO.Read}. */
public class RegistryJpaReadTest {

  private static final DateTime START_TIME = DateTime.parse("2000-01-01T00:00:00.0Z");

  private final FakeClock fakeClock = new FakeClock(START_TIME);

  @RegisterExtension
  @Order(Order.DEFAULT - 1)
  final transient DatastoreEntityExtension datastore = new DatastoreEntityExtension();

  // The pipeline runner on Kokoro sometimes mistakes the platform as appengine, resulting in
  // a null thread factory. The cause is unknown but it may be due to the interaction with
  // the DatastoreEntityExtension above. To work around the problem, we explicitly unset the
  // relevant property before test starts.
  @RegisterExtension
  final transient SystemPropertyExtension systemPropertyExtension =
      new SystemPropertyExtension().setProperty("com.google.appengine.runtime.environment", null);

  @RegisterExtension
  final transient JpaIntegrationTestExtension database =
      new JpaTestRules.Builder().withClock(fakeClock).buildIntegrationTestRule();

  @RegisterExtension
  final transient TestPipelineExtension testPipeline =
      TestPipelineExtension.create().enableAbandonedNodeEnforcement(true);

  private transient ImmutableList<ContactResource> contacts;

  @BeforeEach
  void beforeEach() {
    Registrar ofyRegistrar = AppEngineExtension.makeRegistrar2();
    jpaTm().transact(() -> jpaTm().put(ofyRegistrar));

    ImmutableList.Builder<ContactResource> builder = new ImmutableList.Builder<>();

    for (int i = 0; i < 3; i++) {
      ContactResource contact = DatabaseHelper.newContactResource("contact_" + i);
      builder.add(contact);
    }
    contacts = builder.build();
    jpaTm().transact(() -> jpaTm().putAll(contacts));
  }

  @Test
  void nonTransactionalQuery_noDedupe() {
    Read<ContactResource, String> read =
        RegistryJpaIO.read(
            (JpaTransactionManager jpaTm) -> jpaTm.createQueryComposer(ContactResource.class),
            ContactBase::getContactId);
    PCollection<String> repoIds = testPipeline.apply(read);

    PAssert.that(repoIds).containsInAnyOrder("contact_0", "contact_1", "contact_2");
    testPipeline.run();
  }

  @Test
  void nonTransactionalQuery_dedupe() {
    // This method only serves as an example of deduplication. Duplicates are not actually added.
    Read<ContactResource, KV<String, String>> read =
        RegistryJpaIO.read(
                (JpaTransactionManager jpaTm) -> jpaTm.createQueryComposer(ContactResource.class),
                contact -> KV.of(contact.getRepoId(), contact.getContactId()))
            .withCoder(KvCoder.of(StringUtf8Coder.of(), StringUtf8Coder.of()));
    PCollection<String> repoIds =
        testPipeline
            .apply(read)
            .apply("Deduplicate", Deduplicate.keyedValues())
            .apply("Get values", Values.create());

    PAssert.that(repoIds).containsInAnyOrder("contact_0", "contact_1", "contact_2");
    testPipeline.run();
  }
}
