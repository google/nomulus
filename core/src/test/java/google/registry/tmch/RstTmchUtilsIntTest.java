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
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.util.RegistryEnvironment.PRODUCTION;
import static google.registry.util.RegistryEnvironment.SANDBOX;
import static org.joda.time.DateTime.now;
import static org.joda.time.DateTimeZone.UTC;

import com.google.common.base.Splitter;
import google.registry.model.smd.SignedMarkRevocationList;
import google.registry.model.smd.SignedMarkRevocationListDao;
import google.registry.model.tmch.ClaimsListDao;
import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.testing.FakeClock;
import google.registry.util.RegistryEnvironment;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

public class RstTmchUtilsIntTest {
  private final FakeClock clock = new FakeClock();

  @RegisterExtension
  final JpaTestExtensions.JpaIntegrationTestExtension jpa =
      new JpaTestExtensions.Builder().withClock(clock).buildIntegrationTestExtension();

  private static final String TMCH_CLAIM_LABEL = "tmch";
  // RST label found in *.rst.dnl.csv resources. Currently both files are identical
  private static final String RST_CLAIM_LABEL = "test--validate";

  private static final String TMCH_SMD_ID = "tmch";
  // RST label found in *.rst.smdrl.csv resources. Currently both files are identical
  private static final String RST_SMD_ID = "0000001761385117375880-65535";

  private static final String TMCH_DNL =
      """
      1,2024-09-13T02:21:12.0Z
      DNL,lookup-key,insertion-datetime
      LABEL,2024091300/6/a/b/arJyPPf2CK7f21bVGne0qMgW0000000001,2024-09-13T02:21:12.0Z
      """
          .replace("LABEL", TMCH_CLAIM_LABEL);

  private static final String TMCH_SMDRL =
      """
      1,2022-11-22T01:49:36.9Z
      smd-id,insertion-datetime
      ID,2013-07-15T00:00:00.0Z
      """
          .replace("ID", TMCH_SMD_ID);

  @BeforeEach
  void setup() throws Exception {
    Splitter lineSplitter = Splitter.on("\n").omitEmptyStrings().trimResults();
    tm().transact(
            () -> ClaimsListDao.save(ClaimsListParser.parse(lineSplitter.splitToList(TMCH_DNL))));
    tm().transact(
            () ->
                SignedMarkRevocationListDao.save(
                    SmdrlCsvParser.parse(lineSplitter.splitToList(TMCH_SMDRL))));
  }

  @ParameterizedTest
  @MethodSource("provideTestCases")
  @SuppressWarnings("unused") // testCaseName
  void getClaimsList_production(String testCaseName, String tld) {
    var currEnv = RegistryEnvironment.get();
    try {
      PRODUCTION.setup();
      var claimsList = ClaimsListDao.get(tld);
      assertThat(claimsList.getClaimKey(TMCH_CLAIM_LABEL)).isPresent();
      assertThat(claimsList.getClaimKey(RST_CLAIM_LABEL)).isEmpty();
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
      var smdrl = SignedMarkRevocationList.get(tld);
      assertThat(smdrl.isSmdRevoked(TMCH_SMD_ID, now(UTC))).isTrue();
      assertThat(smdrl.isSmdRevoked(RST_SMD_ID, now(UTC))).isFalse();
      assertThat(smdrl.size()).isEqualTo(1);
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
      var claimsList = ClaimsListDao.get(tld);
      if (tld.equals("app")) {
        assertThat(claimsList.getClaimKey(TMCH_CLAIM_LABEL)).isPresent();
        assertThat(claimsList.getClaimKey(RST_CLAIM_LABEL)).isEmpty();
      } else {
        assertThat(claimsList.getClaimKey(TMCH_CLAIM_LABEL)).isEmpty();
        // Currently ote and prod have the same data.
        assertThat(claimsList.getClaimKey(RST_CLAIM_LABEL)).isPresent();
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
      var smdrList = SignedMarkRevocationList.get(tld);
      if (tld.equals("app")) {
        assertThat(smdrList.size()).isEqualTo(1);
        assertThat(smdrList.isSmdRevoked(TMCH_SMD_ID, now(UTC))).isTrue();
        assertThat(smdrList.isSmdRevoked(RST_SMD_ID, now(UTC))).isFalse();
      } else {
        // Currently ote and prod have the same data.
        assertThat(smdrList.size()).isEqualTo(5);
        assertThat(smdrList.isSmdRevoked(TMCH_SMD_ID, now())).isFalse();
        assertThat(smdrList.isSmdRevoked(RST_SMD_ID, now())).isTrue();
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
