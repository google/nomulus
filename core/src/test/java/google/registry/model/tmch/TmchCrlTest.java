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

package google.registry.model.tmch;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.model.ofy.ObjectifyService.ofy;
import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;

import google.registry.model.EntityTestCase;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link TmchCrl}. */
public class TmchCrlTest extends EntityTestCase {

  TmchCrlTest() {
    super(JpaEntityCoverageCheck.ENABLED);
  }

  @Test
  void testSuccess() {
    assertThat(TmchCrl.get()).isEqualTo(Optional.empty());
    TmchCrl.set("lolcat", "https://lol.cat");
    assertThat(TmchCrl.get().get().getCrl()).isEqualTo("lolcat");
  }

  @Test
  void testDualWrite() {
    TmchCrl expected = new TmchCrl();
    expected.crl = "lolcat";
    expected.url = "https://lol.cat";
    expected.updated = fakeClock.nowUtc();
    TmchCrl.set("lolcat", "https://lol.cat");
    assertThat(ofy().load().entity(new TmchCrl()).now()).isEqualTo(expected);
    assertThat(loadFromSql()).isEqualTo(expected);
  }

  private static TmchCrl loadFromSql() {
    return jpaTm()
        .transact(
            () ->
                jpaTm()
                    .getEntityManager()
                    .createQuery("FROM TmchCrl", TmchCrl.class)
                    .setMaxResults(1)
                    .getResultStream()
                    .findFirst()
                    .get());
  }
}
