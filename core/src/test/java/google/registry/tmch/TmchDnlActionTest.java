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

package google.registry.tmch;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.truth.Truth.assertThat;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import google.registry.model.tmch.ClaimsList;
import google.registry.model.tmch.ClaimsListDao;
import java.io.ByteArrayInputStream;
import java.net.URL;
import java.util.Optional;
import org.joda.time.DateTime;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link TmchDnlAction}. */
class TmchDnlActionTest extends TmchActionTestCase {

  private TmchDnlAction newTmchDnlAction() {
    TmchDnlAction action = new TmchDnlAction();
    action.marksdb = marksdb;
    action.marksdbDnlLoginAndPassword = Optional.of(MARKSDB_LOGIN_AND_PASSWORD);
    return action;
  }

  @Test
  void testDnl() throws Exception {
    assertThat(tm().transact(() -> ClaimsListDao.get().getClaimKey("xn----7sbejwbn3axu3d")))
        .isEmpty();
    when(httpUrlConnection.getInputStream())
        .thenReturn(new ByteArrayInputStream(TmchTestData.loadBytes("dnl/dnl-latest.csv").read()))
        .thenReturn(new ByteArrayInputStream(TmchTestData.loadBytes("dnl/dnl-latest.sig").read()));
    newTmchDnlAction().run();
    verify(httpUrlConnection, times(2)).getInputStream();
    assertThat(
            urlConnectionService.getConnectedUrls().stream()
                .map(URL::toString)
                .collect(toImmutableList()))
        .containsExactly(MARKSDB_URL + "/dnl/dnl-latest.csv", MARKSDB_URL + "/dnl/dnl-latest.sig");

    // Make sure the contents of testdata/dnl-latest.csv got inserted into the database.
    ClaimsList claimsList = ClaimsListDao.get();
    assertThat(claimsList.getTmdbGenerationTime())
        .isEqualTo(DateTime.parse("2013-11-24T23:15:37.4Z"));
    assertThat(tm().transact(() -> claimsList.getClaimKey("xn----7sbejwbn3axu3d")))
        .hasValue("2013112500/7/4/8/dIHW0DiuybvhdP8kIz");
    assertThat(tm().transact(() -> claimsList.getClaimKey("lolcat"))).isEmpty();
  }
}
