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

package google.registry.bsa;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.persistResource;
import static google.registry.tldconfig.idn.IdnTableEnum.EXTENDED_LATIN;
import static google.registry.tldconfig.idn.IdnTableEnum.JA;
import static google.registry.tldconfig.idn.IdnTableEnum.UNCONFUSABLE_LATIN;

import com.google.common.collect.ImmutableSet;
import google.registry.model.tld.Tld;
import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.persistence.transaction.JpaTestExtensions.JpaIntegrationWithCoverageExtension;
import google.registry.testing.FakeClock;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link IdnChecker}. */
public class IdnCheckerTest {

  FakeClock fakeClock = new FakeClock();

  @RegisterExtension
  final JpaIntegrationWithCoverageExtension jpa =
      new JpaTestExtensions.Builder().withClock(fakeClock).buildIntegrationWithCoverageExtension();

  Tld jaonly;
  Tld jandelatin;
  Tld strictlatin;
  IdnChecker idnChecker;

  @BeforeEach
  void setup() {
    jaonly = createTld("jaonly");
    jandelatin = createTld("jandelatin");
    strictlatin = createTld("strictlatin");

    jaonly =
        persistResource(
            jaonly
                .asBuilder()
                .setBsaEnrollStartTime(Optional.of(fakeClock.nowUtc()))
                .setIdnTables(ImmutableSet.of(JA))
                .build());
    jandelatin =
        persistResource(
            jandelatin
                .asBuilder()
                .setBsaEnrollStartTime(Optional.of(fakeClock.nowUtc()))
                .setIdnTables(ImmutableSet.of(JA, EXTENDED_LATIN))
                .build());
    strictlatin =
        persistResource(
            strictlatin
                .asBuilder()
                .setBsaEnrollStartTime(Optional.of(fakeClock.nowUtc()))
                .setIdnTables(ImmutableSet.of(UNCONFUSABLE_LATIN))
                .build());
    fakeClock.advanceOneMilli();
    idnChecker = new IdnChecker(fakeClock);
  }

  @Test
  void getAllValidIdns_allTlds() {
    assertThat(idnChecker.getAllValidIdns("all"))
        .containsExactly(EXTENDED_LATIN, JA, UNCONFUSABLE_LATIN);
  }

  @Test
  void getAllValidIdns_notJa() {
    assertThat(idnChecker.getAllValidIdns("à")).containsExactly(EXTENDED_LATIN, UNCONFUSABLE_LATIN);
  }

  @Test
  void getAllValidIdns_extendedLatinOnly() {
    assertThat(idnChecker.getAllValidIdns("á")).containsExactly(EXTENDED_LATIN);
  }

  @Test
  void getAllValidIdns_jaOnly() {
    assertThat(idnChecker.getAllValidIdns("っ")).containsExactly(JA);
  }

  @Test
  void getAllValidIdns_none() {
    assertThat(idnChecker.getAllValidIdns("д")).isEmpty();
  }

  @Test
  void getSupportingTlds_singleTld_success() {
    assertThat(idnChecker.getSupportingTlds(ImmutableSet.of("EXTENDED_LATIN")))
        .containsExactly(jandelatin);
  }

  @Test
  void getSupportingTlds_multiTld_success() {
    assertThat(idnChecker.getSupportingTlds(ImmutableSet.of("JA")))
        .containsExactly(jandelatin, jaonly);
  }

  @Test
  void getForbiddingTlds_success() {
    assertThat(idnChecker.getForbiddingTlds(ImmutableSet.of("JA"))).containsExactly(strictlatin);
  }
}
