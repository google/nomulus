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

package google.registry.model.console;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.loadByEntity;
import static google.registry.testing.DatabaseHelper.persistDomainWithDependentResources;
import static google.registry.testing.DatabaseHelper.persistResource;

import google.registry.model.EntityTestCase;
import google.registry.testing.DatabaseHelper;
import google.registry.util.DateTimeUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class ConsoleUpdateHistoryTest extends EntityTestCase {
  ConsoleUpdateHistoryTest() {
    super(JpaEntityCoverageCheck.ENABLED);
  }

  @BeforeEach
  void beforeEach() {
    createTld("tld");
    persistDomainWithDependentResources(
        "example",
        "tld",
        fakeClock.nowUtc(),
        fakeClock.nowUtc(),
        DateTimeUtils.END_OF_TIME);
  }

  @Test
  void testPersistence() {
    User user = persistResource(DatabaseHelper.createAdminUser("email@email.com"));
    ConsoleUpdateHistory history =
        new ConsoleUpdateHistory.Builder()
            .setType(ConsoleUpdateHistory.Type.DOMAIN_SUSPEND)
            .setActingUser(user)
            .setMethod("POST")
            .setUrl("/console-api/bulk-domain")
            .setDescription("example.tld")
            .setModificationTime(fakeClock.nowUtc())
            .build();
    persistResource(history);
    assertThat(loadByEntity(history)).isEqualTo(history);
  }
}
