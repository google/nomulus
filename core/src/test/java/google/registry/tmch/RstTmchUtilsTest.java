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

import static com.google.common.truth.Truth.assertThat;
import static google.registry.tmch.RstTmchUtils.getClaimsList;
import static google.registry.tmch.RstTmchUtils.getSmdrList;
import static google.registry.util.RegistryEnvironment.PRODUCTION;
import static google.registry.util.RegistryEnvironment.SANDBOX;

import google.registry.util.RegistryEnvironment;
import java.util.stream.Stream;
import org.joda.time.DateTime;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

public class RstTmchUtilsTest {

  @ParameterizedTest
  @MethodSource("provideTestCases")
  @SuppressWarnings("unused") // testCaseName
  void getClaimsList_production(String testCaseName, String tld) {
    var currEnv = RegistryEnvironment.get();
    try {
      PRODUCTION.setup();
      assertThat(getClaimsList(tld)).isEmpty();
    } finally {
      currEnv.setup();
    }
  }

  @ParameterizedTest
  @MethodSource("provideTestCases")
  @SuppressWarnings("unused") // testCaseName
  void getSmdrList_production(String testCaseName, String tld) {
    var currEnv = RegistryEnvironment.get();
    try {
      PRODUCTION.setup();
      assertThat(getSmdrList(tld)).isEmpty();
    } finally {
      currEnv.setup();
    }
  }

  @ParameterizedTest
  @MethodSource("provideTestCases")
  @SuppressWarnings("unused") // testCaseName
  void getClaimsList_sandbox(String testCaseName, String tld) {
    var currEnv = RegistryEnvironment.get();
    try {
      SANDBOX.setup();
      var claimsListOptional = getClaimsList(tld);
      if (tld.equals("app")) {
        assertThat(claimsListOptional).isEmpty();
      } else {
        // Currently ote and prod have the same data.
        var claimsList = claimsListOptional.get();
        assertThat(claimsList.getClaimKey("test-and-validate")).isPresent();
        var labelsToKeys = claimsList.getLabelsToKeys();
        assertThat(labelsToKeys).hasSize(8);
        assertThat(labelsToKeys)
            .containsEntry(
                "test---validate", "2024091300/6/a/b/arJyPPf2CK7f21bVGne0qMgW0000000001");
      }
    } finally {
      currEnv.setup();
    }
  }

  @ParameterizedTest
  @MethodSource("provideTestCases")
  @SuppressWarnings("unused") // testCaseName
  void getSmdrList_sandbox(String testCaseName, String tld) {
    var currEnv = RegistryEnvironment.get();
    try {
      SANDBOX.setup();
      var smdrListOptional = getSmdrList(tld);
      if (tld.equals("app")) {
        assertThat(smdrListOptional).isEmpty();
      } else {
        // Currently ote and prod have the same data.
        var smdrList = smdrListOptional.get();
        assertThat(smdrList.size()).isEqualTo(5);
        assertThat(
                smdrList.isSmdRevoked(
                    "000000541526299609231-65535", DateTime.parse("2018-05-14T17:52:23.6Z")))
            .isFalse();
        assertThat(
                smdrList.isSmdRevoked(
                    "000000541526299609231-65535", DateTime.parse("2018-05-14T17:52:23.7Z")))
            .isTrue();
      }
    } finally {
      currEnv.setup();
    }
  }

  private static Stream<Arguments> provideTestCases() {
    return Stream.of(
        Arguments.of("NotRST", "app"),
        Arguments.of("OTE", "cc-rst-test-tld-1"),
        Arguments.of("PROD", "zz--idn-123"));
  }
}
