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

package google.registry.beam.comparedb;

import static com.google.common.truth.Truth8.assertThat;
import static google.registry.beam.comparedb.ValidateSqlUtils.getMedianIdForHistoryTable;
import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;
import static org.joda.time.DateTimeZone.UTC;

import com.google.common.truth.Truth;
import google.registry.model.bulkquery.TestSetupHelper;
import google.registry.model.domain.DomainHistory;
import google.registry.testing.AppEngineExtension;
import google.registry.testing.FakeClock;
import org.joda.time.DateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link ValidateSqlUtils}. */
class ValidateSqlUtilsTest {

  private final FakeClock fakeClock = new FakeClock(DateTime.now(UTC));

  private final TestSetupHelper setupHelper = new TestSetupHelper(fakeClock);

  @RegisterExtension
  public final AppEngineExtension appEngine =
      AppEngineExtension.builder().withDatastoreAndCloudSql().withClock(fakeClock).build();

  @Test
  void getMedianIdForHistoryTable_emptyTable() {
    assertThat(getMedianIdForHistoryTable("DomainHistory")).isEmpty();
  }

  @Test
  void getMedianIdForHistoryTable_oneRow() {
    setupHelper.initializeAllEntities();
    Truth.assertThat(jpaTm().transact(() -> jpaTm().loadAllOf(DomainHistory.class))).hasSize(1);
    assertThat(getMedianIdForHistoryTable("DomainHistory"))
        .hasValue(setupHelper.domainHistory.getId());
  }

  @Test
  void getMedianIdForHistoryTable_twoRows() {
    setupHelper.initializeAllEntities();
    setupHelper.applyChangeToDomainAndHistory();
    Truth.assertThat(jpaTm().transact(() -> jpaTm().loadAllOf(DomainHistory.class))).hasSize(2);
    assertThat(getMedianIdForHistoryTable("DomainHistory"))
        .hasValue(setupHelper.domainHistory.getId());
  }
}
