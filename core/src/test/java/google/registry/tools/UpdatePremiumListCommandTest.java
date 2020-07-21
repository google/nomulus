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

package google.registry.tools;

import static google.registry.request.JsonResponse.JSON_SAFETY_PREFIX;
import static google.registry.testing.TestDataHelper.loadFile;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableMap;
import com.google.common.net.MediaType;
import google.registry.tools.server.UpdatePremiumListAction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

/** Unit tests for {@link UpdatePremiumListCommand}. */
class UpdatePremiumListCommandTest<C extends UpdatePremiumListCommand>
    extends CreateOrUpdatePremiumListCommandTestCase<C> {

  @Mock AppEngineConnection connection;

  private String premiumTermsPath;
  String premiumTermsCsv;
  private String servletPath;

  @BeforeEach
  void beforeEach() throws Exception {
    command.setConnection(connection);
    servletPath = "/_dr/admin/updatePremiumList";
    premiumTermsPath =
        writeToNamedTmpFile(
            "example_premium_terms.csv",
            loadFile(UpdatePremiumListCommandTest.class, "example_premium_terms.csv"));
    when(connection.sendPostRequest(
            eq(UpdatePremiumListAction.PATH), anyMap(), any(MediaType.class), any(byte[].class)))
        .thenReturn(JSON_SAFETY_PREFIX + "{\"status\":\"success\",\"lines\":[]}");
  }

  @Test
  void testRun() throws Exception {
    runCommandForced("-i=" + premiumTermsPath, "-n=foo");
    verifySentParams(
        connection,
        servletPath,
        ImmutableMap.of("name", "foo", "inputData", generateInputData(premiumTermsPath)));
  }

  @Test
  void testRun_noProvidedName_usesBasenameOfInputFile() throws Exception {
    runCommandForced("-i=" + premiumTermsPath);
    assertInStdout("Successfully");
    verifySentParams(
        connection,
        servletPath,
        ImmutableMap.of(
            "name", "example_premium_terms", "inputData", generateInputData(premiumTermsPath)));
  }
}
