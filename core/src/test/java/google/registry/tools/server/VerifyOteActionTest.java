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

package google.registry.tools.server;

import static com.google.common.truth.Truth.assertThat;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import google.registry.model.OteStatsTestHelper;
import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.persistence.transaction.JpaTestExtensions.JpaIntegrationTestExtension;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link VerifyOteAction}. */
class VerifyOteActionTest {

  @RegisterExtension
  final JpaIntegrationTestExtension jpa =
      new JpaTestExtensions.Builder().buildIntegrationTestExtension();

  private final VerifyOteAction action = new VerifyOteAction();

  @Test
  void testSuccess_summarize_allPass() throws Exception {
    OteStatsTestHelper.setupCompleteOte("blobio");
    assertThat(getResponse(true))
        .isEqualTo("# actions:   30 - Reqs: [----------------] 16/16 - Overall: PASS");
  }

  @Test
  void testFailure_summarize_someFailures() throws Exception {
    OteStatsTestHelper.setupIncompleteOte("blobio");
    assertThat(getResponse(true))
        .isEqualTo("# actions:   34 - Reqs: [-.-----.------.-] 13/16 - Overall: FAIL");
  }

  @Test
  void testSuccess_passNotSummarized() throws Exception {
    OteStatsTestHelper.setupCompleteOte("blobio");
    String expectedOteStatus =
        """
            domain creates idn: 1
            domain creates start date sunrise: 1
            domain creates with claims notice: 1
            domain creates with fee: 1
            domain creates with sec dns: 1
            .*domain deletes: 1
            .*domain restores: 1
            domain transfer approves: 1
            domain transfer cancels: 1
            domain transfer rejects: 1
            domain transfer requests: 1
            .*domain updates with sec dns: 1
            .*host creates subordinate: 1
            host deletes: 1
            host updates: 1
            .*Requirements passed: 16/16
            Overall OT&E status: PASS
            """;
    Pattern expectedOteStatusPattern = Pattern.compile(expectedOteStatus, Pattern.DOTALL);
    assertThat(getResponse(false)).containsMatch(expectedOteStatusPattern);
  }

  @Test
  void testFailure_incomplete() throws Exception {
    OteStatsTestHelper.setupIncompleteOte("blobio");
    String expectedOteStatus =
        """
            domain creates idn: 0
            domain creates start date sunrise: 1
            domain creates with claims notice: 1
            domain creates with fee: 1
            domain creates with sec dns: 1
            .*domain deletes: 1
            .*domain restores: 0
            domain transfer approves: 1
            domain transfer cancels: 1
            domain transfer rejects: 1
            domain transfer requests: 1
            .*domain updates with sec dns: 1
            .*host creates subordinate: 1
            host deletes: 0
            host updates: 10
            .*Requirements passed: 13/16
            Overall OT&E status: FAIL
            """;
    Pattern expectedOteStatusPattern = Pattern.compile(expectedOteStatus, Pattern.DOTALL);
    assertThat(getResponse(false)).containsMatch(expectedOteStatusPattern);
  }

  private String getResponse(boolean summarize) {
    Map<String, Object> response =
        action.handleJsonRequest(
            ImmutableMap.of(
                "summarize",
                Boolean.toString(summarize),
                "registrars",
                ImmutableList.of("blobio")));
    assertThat(response).containsKey("blobio");
    return response.get("blobio").toString();
  }
}
