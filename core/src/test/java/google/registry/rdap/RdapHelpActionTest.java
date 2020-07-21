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

package google.registry.rdap;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.rdap.RdapTestHelper.loadJsonFile;
import static org.mockito.Mockito.verify;

import google.registry.rdap.RdapMetrics.EndpointType;
import google.registry.rdap.RdapMetrics.SearchType;
import google.registry.rdap.RdapMetrics.WildcardType;
import google.registry.rdap.RdapSearchResults.IncompletenessWarningType;
import google.registry.request.Action;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link RdapHelpAction}. */
class RdapHelpActionTest extends RdapActionBaseTestCase<RdapHelpAction> {

  RdapHelpActionTest() {
    super(RdapHelpAction.class);
  }

  @Test
  void testHelpActionMaliciousPath_notFound() {
    assertThat(generateActualJson("../passwd"))
        .isEqualTo(generateExpectedJsonError("no help found for ../passwd", 404));
    assertThat(response.getStatus()).isEqualTo(404);
  }

  @Test
  void testHelpActionUnknownPath_notFound() {
    assertThat(generateActualJson("hlarg"))
        .isEqualTo(generateExpectedJsonError("no help found for hlarg", 404));
    assertThat(response.getStatus()).isEqualTo(404);
  }

  @Test
  void testHelpActionDefault_getsIndex() {
    assertThat(generateActualJson("")).isEqualTo(loadJsonFile("rdap_help_index.json"));
    assertThat(response.getStatus()).isEqualTo(200);
  }

  @Test
  void testHelpActionSlash_getsIndex() {
    assertThat(generateActualJson("/")).isEqualTo(loadJsonFile("rdap_help_index.json"));
    assertThat(response.getStatus()).isEqualTo(200);
  }

  @Test
  void testHelpActionTos_works() {
    assertThat(generateActualJson("/tos")).isEqualTo(loadJsonFile("rdap_help_tos.json"));
    assertThat(response.getStatus()).isEqualTo(200);
  }

  @Test
  void testHelpActionMetrics() {
    generateActualJson("/tos");
    verify(rdapMetrics)
        .updateMetrics(
            RdapMetrics.RdapMetricInformation.builder()
                .setEndpointType(EndpointType.HELP)
                .setSearchType(SearchType.NONE)
                .setWildcardType(WildcardType.INVALID)
                .setPrefixLength(0)
                .setIncludeDeleted(false)
                .setRegistrarSpecified(false)
                .setRole(RdapAuthorization.Role.PUBLIC)
                .setRequestMethod(Action.Method.GET)
                .setStatusCode(200)
                .setIncompletenessWarningType(IncompletenessWarningType.COMPLETE)
                .build());
  }
}
