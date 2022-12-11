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

package google.registry.beam.common;

import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static com.google.common.truth.Truth.assertThat;
import static google.registry.model.ImmutableObjectSubject.immutableObjectCorrespondence;
import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;
import static google.registry.testing.DatabaseHelper.loadAllOf;
import static google.registry.testing.DatabaseHelper.newContact;
import static google.registry.testing.DatabaseHelper.newHost;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import google.registry.beam.TestPipelineExtension;
import google.registry.beam.common.RegistryJpaIO.ExistingEntityException;
import google.registry.model.contact.Contact;
import google.registry.model.host.Host;
import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.persistence.transaction.JpaTestExtensions.JpaIntegrationTestExtension;
import google.registry.testing.FakeClock;
import java.io.Serializable;
import org.apache.beam.sdk.Pipeline.PipelineExecutionException;
import org.apache.beam.sdk.coders.SerializableCoder;
import org.apache.beam.sdk.options.PipelineOptionsFactory;
import org.apache.beam.sdk.transforms.Create;
import org.apache.beam.sdk.values.KV;
import org.joda.time.DateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit test for {@link RegistryJpaIO.Write}. */
class RegistryJpaWriteTest implements Serializable {

  private final FakeClock fakeClock = new FakeClock(DateTime.parse("2000-01-01T00:00:00.0Z"));

  @RegisterExtension
  final transient JpaIntegrationTestExtension database =
      new JpaTestExtensions.Builder().withClock(fakeClock).buildIntegrationTestExtension();

  @RegisterExtension
  final transient TestPipelineExtension testPipeline =
      TestPipelineExtension.fromOptions(
              PipelineOptionsFactory.fromArgs("--targetParallelism=1").create())
          .enableAbandonedNodeEnforcement(true);

  @Test
  void writeToSql_twoWriters() {
    ImmutableList.Builder<Contact> contactsBuilder = new ImmutableList.Builder<>();
    for (int i = 0; i < 3; i++) {
      contactsBuilder.add(newContact("contact_" + i));
    }
    ImmutableList<Contact> contacts = contactsBuilder.build();
    testPipeline
        .apply(Create.of(contacts))
        .apply(RegistryJpaIO.<Contact>write().withName("Contact").withBatchSize(4).withShards(2));
    testPipeline.run().waitUntilFinish();

    assertThat(loadAllOf(Contact.class))
        .comparingElementsUsing(immutableObjectCorrespondence("updateTimestamp"))
        .containsExactlyElementsIn(contacts);
  }

  @Test
  void writeToSql_iterableEntities() {
    ImmutableList.Builder<KV<Contact, Host>> kvBuilder = new ImmutableList.Builder<>();
    for (int i = 0; i < 3; i++) {
      kvBuilder.add(KV.of(newContact("contact_" + i), newHost("host_" + i + ".external")));
    }
    ImmutableList<KV<Contact, Host>> kvs = kvBuilder.build();
    testPipeline
        .apply(Create.of(kvs))
        .apply(
            RegistryJpaIO.<KV<Contact, Host>>write()
                .withName("Contact and Host")
                .withJpaConverter(kv -> ImmutableList.of(kv.getKey(), kv.getValue()))
                .withBatchSize(4)
                .withShards(2));
    testPipeline.run().waitUntilFinish();
    assertThat(loadAllOf(Contact.class))
        .comparingElementsUsing(immutableObjectCorrespondence("updateTimestamp"))
        .containsExactlyElementsIn(kvs.stream().map(KV::getKey).collect(toImmutableSet()));
    assertThat(loadAllOf(Host.class))
        .comparingElementsUsing(immutableObjectCorrespondence("updateTimestamp"))
        .containsExactlyElementsIn(kvs.stream().map(KV::getValue).collect(toImmutableSet()));
  }

  @Test
  void testFailure_noRetrySingly() {
    ImmutableList.Builder<Serializable> entityBuilder = new ImmutableList.Builder<>();
    for (int i = 0; i < 3; i++) {
      entityBuilder.add(newContact("contact_" + i));
    }
    entityBuilder.add(new Serializable() {});
    ImmutableList<Serializable> entities = entityBuilder.build();
    testPipeline
        .apply(Create.of(entities).withCoder(SerializableCoder.of(Serializable.class)))
        .apply(
            RegistryJpaIO.<Serializable>write()
                .withName("Contact")
                // Because there is only one worker thread (see testPipeline construction), all four
                // elements must be grouped into one batch.
                .withBatchSize(4)
                .withRetrySingly(false));
    assertThrows(PipelineExecutionException.class, () -> testPipeline.run().waitUntilFinish());
    // All three contacts are in the same batch, and none should have been persisted.
    assertThat(loadAllOf(Contact.class)).isEmpty();
  }

  @Test
  void testFailure_writeExistingEntity() {
    // RegistryJpaIO.Write actions should not write existing objects to the database because the
    // object could have been mutated in between creation and when the Write actually occurs,
    // causing a race condition
    jpaTm()
        .transact(
            () -> {
              jpaTm().put(newHost("blah.external"));
              jpaTm().put(newContact("contact"));
            });
    Contact contact = Iterables.getOnlyElement(loadAllOf(Contact.class));
    testPipeline
        .apply(Create.of(contact))
        .apply(RegistryJpaIO.<Contact>write().withName("Contact"));
    // PipelineExecutionException caused by an ExistingEntityException ultimately.
    assertThat(
            Throwables.getRootCause(
                assertThrows(
                    PipelineExecutionException.class, () -> testPipeline.run().waitUntilFinish())))
        .isInstanceOf(ExistingEntityException.class);
  }
}
