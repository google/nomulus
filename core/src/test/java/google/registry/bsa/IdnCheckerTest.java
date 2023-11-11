// Copyright 2023 The Nomulus Authors. All Rights Reserved.
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
import static google.registry.tldconfig.idn.IdnTableEnum.EXTENDED_LATIN;
import static google.registry.tldconfig.idn.IdnTableEnum.JA;
import static google.registry.tldconfig.idn.IdnTableEnum.UNCONFUSABLE_LATIN;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import google.registry.model.tld.Tld;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class IdnCheckerTest {

  @Mock Tld jaonly;
  @Mock Tld jandelatin;
  @Mock Tld strictlatin;
  IdnChecker idnChecker;

  @BeforeEach
  void setup() {
    idnChecker =
        new IdnChecker(
            ImmutableMap.of(
                JA,
                ImmutableSet.of(jandelatin, jaonly),
                EXTENDED_LATIN,
                ImmutableSet.of(jandelatin),
                UNCONFUSABLE_LATIN,
                ImmutableSet.of(strictlatin)));
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
