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

package google.registry.beam.common;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.model.ImmutableObjectSubject.immutableObjectCorrespondence;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.testing.DatabaseHelper.loadAllOf;
import static google.registry.testing.DatabaseHelper.newContact;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import google.registry.beam.TestPipelineExtension;
import google.registry.model.contact.Contact;
import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.persistence.transaction.JpaTestExtensions.JpaIntegrationTestExtension;
import google.registry.persistence.transaction.JpaTransactionManagerExtension;
import google.registry.testing.FakeClock;
import java.io.Serializable;
import org.apache.beam.sdk.Pipeline.PipelineExecutionException;
import org.apache.beam.sdk.transforms.Create;
import org.joda.time.DateTime;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit test for {@link RegistryJpaIO.Write}. */
class RegistryJpaWriteTest implements Serializable {

  private final FakeClock fakeClock = new FakeClock(DateTime.parse("2000-01-01T00:00:00.0Z"));

  @RegisterExtension
  final transient JpaIntegrationTestExtension jpa =
      new JpaTestExtensions.Builder().withClock(fakeClock).buildIntegrationTestExtension();

  @RegisterExtension
  final transient TestPipelineExtension testPipeline =
      TestPipelineExtension.create().enableAbandonedNodeEnforcement(true);

  @Test
  void writeToSql_twoWriters() {
    tm().transact(() -> tm().put(JpaTransactionManagerExtension.makeRegistrar2()));
    ImmutableList.Builder<Contact> contactsBuilder = new ImmutableList.Builder<>();
    for (int i = 0; i < 3; i++) {
      contactsBuilder.add(newContact("contact_" + i));
    }
    ImmutableList<Contact> contacts = contactsBuilder.build();
    testPipeline
        .apply(Create.of(contacts))
        .apply(RegistryJpaIO.<Contact>write().withName("Contact").withBatchSize(4));
    testPipeline.run().waitUntilFinish();

    assertThat(loadAllOf(Contact.class))
        .comparingElementsUsing(immutableObjectCorrespondence("revisions", "updateTimestamp"))
        .containsExactlyElementsIn(contacts);
  }

  @Disabled("b/263502442")
  @Test
  void testFailure_writeExistingEntity() {
    // RegistryJpaIO.Write actions should not write existing objects to the database because the
    // object could have been mutated in between creation and when the Write actually occurs,
    // causing a race condition
    tm().transact(
            () -> {
              tm().put(JpaTransactionManagerExtension.makeRegistrar2());
              tm().put(newContact("contact"));
            });
    Contact contact = Iterables.getOnlyElement(loadAllOf(Contact.class));
    testPipeline
        .apply(Create.of(contact))
        .apply(RegistryJpaIO.<Contact>write().withName("Contact"));
    // PipelineExecutionException caused by a RuntimeException caused by an IllegalArgumentException
    assertThat(
            assertThrows(
                PipelineExecutionException.class, () -> testPipeline.run().waitUntilFinish()))
        .hasCauseThat()
        .hasCauseThat()
        .isInstanceOf(IllegalArgumentException.class);
  }
}
