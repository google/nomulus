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

package google.registry.batch;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.model.common.FeatureFlag.FeatureName.MINIMUM_DATASET_CONTACTS_PROHIBITED;
import static google.registry.model.common.FeatureFlag.FeatureStatus.ACTIVE;
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.loadByEntity;
import static google.registry.testing.DatabaseHelper.newDomain;
import static google.registry.testing.DatabaseHelper.persistActiveContact;
import static google.registry.testing.DatabaseHelper.persistResource;
import static google.registry.util.DateTimeUtils.START_OF_TIME;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import google.registry.flows.DaggerEppTestComponent;
import google.registry.flows.EppController;
import google.registry.flows.EppTestComponent.FakesAndMocksModule;
import google.registry.model.common.FeatureFlag;
import google.registry.model.contact.Contact;
import google.registry.model.domain.Domain;
import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.persistence.transaction.JpaTestExtensions.JpaIntegrationTestExtension;
import google.registry.testing.FakeClock;
import google.registry.testing.FakeLockHandler;
import google.registry.testing.FakeResponse;
import java.util.Optional;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link RemoveAllDomainContactsAction}. */
class RemoveAllDomainContactsActionTest {

  @RegisterExtension
  final JpaIntegrationTestExtension jpa =
      new JpaTestExtensions.Builder().buildIntegrationTestExtension();

  private final FakeResponse response = new FakeResponse();
  private RemoveAllDomainContactsAction action;

  @BeforeEach
  void beforeEach() {
    createTld("tld");
    persistResource(
        new FeatureFlag.Builder()
            .setFeatureName(MINIMUM_DATASET_CONTACTS_PROHIBITED)
            .setStatusMap(ImmutableSortedMap.of(START_OF_TIME, ACTIVE))
            .build());
    EppController eppController =
        DaggerEppTestComponent.builder()
            .fakesAndMocksModule(FakesAndMocksModule.create(new FakeClock()))
            .build()
            .startRequest()
            .eppController();
    action =
        new RemoveAllDomainContactsAction(
            eppController, "NewRegistrar", new FakeLockHandler(true), response);
  }

  @Test
  void test_removesAllContactsFromMultipleDomains_andDoesntModifyDomainThatHasNoContacts() {
    Contact c1 = persistActiveContact("contact12345");
    Domain d1 = persistResource(newDomain("foo.tld", c1));
    assertThat(d1.getAllContacts()).hasSize(3);
    Contact c2 = persistActiveContact("contact23456");
    Domain d2 = persistResource(newDomain("bar.tld", c2));
    assertThat(d2.getAllContacts()).hasSize(3);
    Domain d3 =
        persistResource(
            newDomain("baz.tld")
                .asBuilder()
                .setRegistrant(Optional.empty())
                .setContacts(ImmutableSet.of())
                .build());
    assertThat(d3.getAllContacts()).isEmpty();
    DateTime lastUpdate = d3.getUpdateTimestamp().getTimestamp();

    action.run();
    assertThat(loadByEntity(d1).getAllContacts()).isEmpty();
    assertThat(loadByEntity(d2).getAllContacts()).isEmpty();
    assertThat(loadByEntity(d3).getUpdateTimestamp().getTimestamp()).isEqualTo(lastUpdate);
  }

  @Test
  void test_removesContacts_onDomainsThatOnlyPartiallyHaveContacts() {
    Contact c1 = persistActiveContact("contact12345");
    Domain d1 =
        persistResource(
            newDomain("foo.tld", c1).asBuilder().setContacts(ImmutableSet.of()).build());
    assertThat(d1.getAllContacts()).hasSize(1);
    Contact c2 = persistActiveContact("contact23456");
    Domain d2 =
        persistResource(
            newDomain("bar.tld", c2).asBuilder().setRegistrant(Optional.empty()).build());
    assertThat(d2.getAllContacts()).hasSize(2);

    action.run();
    assertThat(loadByEntity(d1).getAllContacts()).isEmpty();
    assertThat(loadByEntity(d2).getAllContacts()).isEmpty();
  }
}
