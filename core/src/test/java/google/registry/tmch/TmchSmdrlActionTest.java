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
import static google.registry.tmch.TmchTestData.loadBytes;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import google.registry.model.smd.SignedMarkRevocationList;
import java.util.Optional;
import org.joda.time.DateTime;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link TmchSmdrlAction}. */
class TmchSmdrlActionTest extends TmchActionTestCase {

  private static final DateTime now = DateTime.parse("2014-01-01T00:00:00Z");

  private TmchSmdrlAction newTmchSmdrlAction() {
    TmchSmdrlAction action = new TmchSmdrlAction();
    action.marksdb = marksdb;
    action.marksdbSmdrlLoginAndPassword = Optional.of("username:password");
    return action;
  }

  @Test
  void testSuccess_smdrl() throws Exception {
    SignedMarkRevocationList smdrl = SignedMarkRevocationList.get();
    assertThat(smdrl.isSmdRevoked("0000001681375789102250-65535", now)).isFalse();
    assertThat(smdrl.isSmdRevoked("0000001681375789102250-65536", now)).isFalse();
    when(httpResponse.getContent())
        .thenReturn(loadBytes("smdrl-latest.csv").read())
        .thenReturn(loadBytes("smdrl-latest.sig").read());
    newTmchSmdrlAction().run();
    verify(fetchService, times(2)).fetch(httpRequest.capture());
    assertThat(httpRequest.getAllValues().get(0).getURL().toString())
        .isEqualTo(MARKSDB_URL + "/smdrl/smdrl-latest.csv");
    assertThat(httpRequest.getAllValues().get(1).getURL().toString())
        .isEqualTo(MARKSDB_URL + "/smdrl/smdrl-latest.sig");
    smdrl = SignedMarkRevocationList.get();
    assertThat(smdrl.isSmdRevoked("0000001681375789102250-65535", now)).isTrue();
    assertThat(smdrl.isSmdRevoked("0000001681375789102250-65536", now)).isFalse();
  }
}
