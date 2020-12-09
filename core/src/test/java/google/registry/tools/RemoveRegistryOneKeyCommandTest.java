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

package google.registry.tools;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.model.ImmutableObjectSubject.immutableObjectCorrespondence;
import static google.registry.model.ofy.ObjectifyService.ofy;
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.newDomainBase;
import static google.registry.testing.DatabaseHelper.persistResource;

import com.google.common.collect.ImmutableList;
import com.google.common.truth.Correspondence;
import com.googlecode.objectify.Key;
import google.registry.model.ImmutableObject;
import google.registry.model.billing.BillingEvent;
import google.registry.model.common.EntityGroupRoot;
import google.registry.model.domain.DomainBase;
import google.registry.model.poll.PollMessage;
import google.registry.model.reporting.HistoryEntry;
import google.registry.persistence.VKey;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit test for {@link RemoveRegistryOneKeyCommand}. */
public class RemoveRegistryOneKeyCommandTest extends CommandTestCase<RemoveRegistryOneKeyCommand> {
  DomainBase domain;
  HistoryEntry historyEntry;

  @BeforeEach
  void beforeEach() {
    createTld("foobar");
    domain =
        newDomainBase("foo.foobar")
            .asBuilder()
            .setDeletionTime(DateTime.parse("2016-01-01T00:00:00Z"))
            .setAutorenewBillingEvent(createRegistryOneVKey(BillingEvent.Recurring.class, 100L))
            .setAutorenewPollMessage(createRegistryOneVKey(PollMessage.Autorenew.class, 200L))
            .setDeletePollMessage(createRegistryOneVKey(PollMessage.OneTime.class, 300L))
            .build();
  }

  @Test
  void removeRegistryOneKeyInDomainBase_succeeds() throws Exception {
    DomainBase origin = persistResource(domain);

    runCommand(
        "--force",
        "--key_paths_file",
        writeToNamedTmpFile("keypath.txt", getKeyPathLiteral(domain)));

    DomainBase persisted = ofy().load().key(domain.createVKey().getOfyKey()).now();
    assertThat(ImmutableList.of(persisted))
        .comparingElementsUsing(getDomainBaseCorrespondence())
        .containsExactly(origin);
    assertThat(persisted.getAutorenewBillingEvent()).isNull();
    assertThat(persisted.getAutorenewPollMessage()).isNull();
    assertThat(persisted.getDeletePollMessage()).isNull();
  }

  @Test
  void removeRegistryOneKeyInDomainBase_notModifyRegistryTwoKey() throws Exception {
    DomainBase origin =
        persistResource(
            domain
                .asBuilder()
                .setAutorenewBillingEvent(
                    createRegistryTwoVKey(BillingEvent.Recurring.class, domain, 300L))
                .build());

    runCommand(
        "--force",
        "--key_paths_file",
        writeToNamedTmpFile("keypath.txt", getKeyPathLiteral(domain)));

    DomainBase persisted = ofy().load().key(domain.createVKey().getOfyKey()).now();
    assertThat(ImmutableList.of(persisted))
        .comparingElementsUsing(getDomainBaseCorrespondence())
        .containsExactly(origin);
    assertThat(persisted.getAutorenewBillingEvent())
        .isEqualTo(createRegistryTwoVKey(BillingEvent.Recurring.class, domain, 300L));
    assertThat(persisted.getAutorenewPollMessage()).isNull();
    assertThat(persisted.getDeletePollMessage()).isNull();
  }

  private static String getKeyPathLiteral(Object entity) {
    Key<?> key = Key.create(entity);
    return String.format("\"DomainBase\", \"%s\"", key.getName());
  }

  private static <T> VKey<T> createRegistryOneVKey(Class<T> clazz, long id) {
    Key<?> parent = Key.create(EntityGroupRoot.class, "per-tld");
    return VKey.create(clazz, id, Key.create(parent, clazz, id));
  }

  private static <T> VKey<T> createRegistryTwoVKey(Class<T> clazz, DomainBase domain, long id) {
    Key<?> parent = Key.create(domain.createVKey().getOfyKey(), HistoryEntry.class, 1000L);
    return VKey.create(clazz, id, Key.create(parent, clazz, id));
  }

  private static Correspondence<ImmutableObject, ImmutableObject> getDomainBaseCorrespondence() {
    return immutableObjectCorrespondence(
        "revisions",
        "updateTimestamp",
        "autorenewBillingEvent",
        "autorenewPollMessage",
        "deletePollMessage");
  }
}
