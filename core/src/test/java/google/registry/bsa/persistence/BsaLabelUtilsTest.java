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

package google.registry.bsa.persistence;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.bsa.persistence.BsaLabelUtils.isLabelBlocked;
import static google.registry.bsa.persistence.BsaTestingUtils.persistBsaLabel;
import static google.registry.persistence.transaction.TransactionManagerFactory.replicaTm;
import static google.registry.persistence.transaction.TransactionManagerFactory.setJpaTm;
import static google.registry.persistence.transaction.TransactionManagerFactory.setReplicaJpaTm;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static org.joda.time.DateTimeZone.UTC;
import static org.joda.time.Duration.millis;
import static org.joda.time.Duration.standardMinutes;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.persistence.transaction.JpaTestExtensions.JpaIntegrationWithCoverageExtension;
import google.registry.persistence.transaction.JpaTransactionManager;
import google.registry.testing.FakeClock;
import org.joda.time.DateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link BsaLabelUtils}. */
public class BsaLabelUtilsTest {

  protected FakeClock fakeClock = new FakeClock(DateTime.now(UTC));

  @RegisterExtension
  final JpaIntegrationWithCoverageExtension jpa =
      new JpaTestExtensions.Builder().withClock(fakeClock).buildIntegrationWithCoverageExtension();

  @Test
  void isLabelBlocked_yes() {
    persistBsaLabel("abc");
    assertThat(isLabelBlocked("abc")).isTrue();
  }

  @Test
  void isLabelBlocked_no() {
    assertThat(isLabelBlocked("abc")).isFalse();
  }

  @Test
  void isLabelBlocked_isCacheUsed_withReplica() throws Throwable {
    JpaTransactionManager primaryTmSave = tm();
    JpaTransactionManager replicaTmSave = replicaTm();

    JpaTransactionManager primaryTm = mock(JpaTransactionManager.class);
    JpaTransactionManager replicaTm = mock(JpaTransactionManager.class);
    setJpaTm(() -> primaryTm);
    setReplicaJpaTm(() -> replicaTm);
    when(replicaTm.loadByKey(any())).thenReturn(new BsaLabel("abc", fakeClock.nowUtc()));
    try {
      assertThat(isLabelBlocked("abc")).isTrue();
      assertThat(isLabelBlocked("abc")).isTrue();
      verify(replicaTm, times(1)).loadByKey(any());
      verify(primaryTm, never()).loadByKey(any());
    } catch (Throwable e) {
      setJpaTm(() -> primaryTmSave);
      setReplicaJpaTm(() -> replicaTmSave);
    }
  }

  @Test
  void isLabelBlocked_isCacheUsed_withOneMinuteExpiry() throws Throwable {
    JpaTransactionManager replicaTmSave = replicaTm();
    JpaTransactionManager replicaTm = mock(JpaTransactionManager.class);
    setReplicaJpaTm(() -> replicaTm);
    when(replicaTm.loadByKey(any())).thenReturn(new BsaLabel("abc", fakeClock.nowUtc()));
    try {
      assertThat(isLabelBlocked("abc")).isTrue();
      // If test fails, check and fix cache expiry in the config file. Do not increase the duration
      // on the line below without proper discussion.
      fakeClock.advanceBy(standardMinutes(1).plus(millis(1)));
      assertThat(isLabelBlocked("abc")).isTrue();
      verify(replicaTm, times(2)).loadByKey(any());
    } catch (Throwable e) {
      setReplicaJpaTm(() -> replicaTmSave);
    }
  }
}
