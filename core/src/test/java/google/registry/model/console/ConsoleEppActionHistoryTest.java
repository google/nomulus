// Copyright 2024 The Nomulus Authors. All Rights Reserved.
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

package google.registry.model.console;

import static com.google.common.collect.Iterables.getOnlyElement;
import static com.google.common.truth.Truth.assertThat;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.persistActiveContact;
import static google.registry.testing.DatabaseHelper.persistDomainWithDependentResources;

import google.registry.model.EntityTestCase;
import google.registry.model.domain.DomainHistory;
import google.registry.testing.DatabaseHelper;
import google.registry.util.DateTimeUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Tests for {@link ConsoleEppActionHistory}. */
public class ConsoleEppActionHistoryTest extends EntityTestCase {

  ConsoleEppActionHistoryTest() {
    super(JpaEntityCoverageCheck.ENABLED);
  }

  @BeforeEach
  void beforeEach() {
    createTld("tld");
    persistDomainWithDependentResources(
        "example",
        "tld",
        persistActiveContact("contact1234"),
        fakeClock.nowUtc(),
        fakeClock.nowUtc(),
        DateTimeUtils.END_OF_TIME);
  }

  @Test
  void testPersistence() {
    User user = DatabaseHelper.createAdminUser("email@email.com");
    DomainHistory domainHistory = getOnlyElement(DatabaseHelper.loadAllOf(DomainHistory.class));
    ConsoleEppActionHistory history =
        new ConsoleEppActionHistory.Builder()
            .setType(ConsoleUpdateHistory.Type.DOMAIN_DELETE)
            .setActingUser(user)
            .setModificationTime(fakeClock.nowUtc())
            .setMethod("POST")
            .setUrl("https://some/url/for/creating/a/domain")
            .setHistoryEntryClass(DomainHistory.class)
            .setHistoryEntryId(domainHistory.getHistoryEntryId())
            .build();
    tm().transact(() -> tm().put(history));
    assertThat(getOnlyElement(DatabaseHelper.loadAllOf(ConsoleEppActionHistory.class)))
        .isEqualTo(history);
  }
}
