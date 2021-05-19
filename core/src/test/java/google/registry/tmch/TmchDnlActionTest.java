// Copyright 2017 The Nomulus Authors. All Rights Reserved.
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

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth8.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import google.registry.model.tmch.ClaimsList;
import google.registry.model.tmch.ClaimsListDao;
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
    assertThat(ClaimsListDao.get().getClaimKey("xn----7sbejwbn3axu3d")).isEmpty();
    when(httpResponse.getContent())
        .thenReturn(TmchTestData.loadBytes("dnl-latest.csv").read())
        .thenReturn(TmchTestData.loadBytes("dnl-latest.sig").read());
    newTmchDnlAction().run();
    verify(fetchService, times(2)).fetch(httpRequest.capture());
    assertThat(httpRequest.getAllValues().get(0).getURL().toString())
        .isEqualTo(MARKSDB_URL + "/dnl/dnl-latest.csv");
    assertThat(httpRequest.getAllValues().get(1).getURL().toString())
        .isEqualTo(MARKSDB_URL + "/dnl/dnl-latest.sig");

    // Make sure the contents of testdata/dnl-latest.csv got inserted into the database.
    ClaimsList claimsList = ClaimsListDao.get();
    assertThat(claimsList.getTmdbGenerationTime())
        .isEqualTo(DateTime.parse("2013-11-24T23:15:37.4Z"));
    assertThat(claimsList.getClaimKey("xn----7sbejwbn3axu3d"))
        .hasValue("2013112500/7/4/8/dIHW0DiuybvhdP8kIz");
    assertThat(claimsList.getClaimKey("lolcat")).isEmpty();
  }
}
