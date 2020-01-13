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

import static com.google.common.truth.Truth.assertThat;
import static google.registry.request.JsonResponse.JSON_SAFETY_PREFIX;
import static google.registry.testing.TestDataHelper.loadFile;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import com.beust.jcommander.ParameterException;
import com.google.common.base.VerifyException;
import com.google.common.collect.ImmutableMap;
import com.google.common.net.MediaType;
import google.registry.tools.server.CreatePremiumListAction;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;

/** Unit tests for {@link CreatePremiumListCommand}. */
public class CreatePremiumListCommandTest<C extends CreatePremiumListCommand>
    extends CreateOrUpdatePremiumListCommandTestCase<C> {

  @Mock AppEngineConnection connection;

  String premiumTermsPath;
  String premiumTermsCsv;
  String servletPath;

  @Before
  public void init() throws Exception {
    command.setConnection(connection);
    premiumTermsPath =
        writeToNamedTmpFile(
            "example_premium_terms.csv",
            loadFile(CreatePremiumListCommandTest.class, "example_premium_terms.csv"));
    servletPath = "/_dr/admin/createPremiumList";
    when(connection.sendPostRequest(
            eq(CreatePremiumListAction.PATH),
            ArgumentMatchers.<String, String>anyMap(),
            any(MediaType.class),
            any(byte[].class)))
        .thenReturn(JSON_SAFETY_PREFIX + "{\"status\":\"success\",\"lines\":[]}");
  }

  @Test
  public void testRun() throws Exception {
    runCommandForced("-i=" + premiumTermsPath, "-n=foo");
    assertInStdout("Successfully");
    verifySentParams(
        connection,
        servletPath,
        ImmutableMap.of("name", "foo", "inputData", generateInputData(premiumTermsPath)));
  }

  @Test
  public void testRun_noProvidedName_usesBasenameOfInputFile() throws Exception {
    runCommandForced("-i=" + premiumTermsPath);
    assertInStdout("Successfully");
    verifySentParams(
        connection,
        servletPath,
        ImmutableMap.of(
            "name", "example_premium_terms", "inputData", generateInputData(premiumTermsPath)));
  }

  @Test
  public void testRun_errorResponse() throws Exception {
    reset(connection);
    command.setConnection(connection);
    when(connection.sendPostRequest(
            eq(CreatePremiumListAction.PATH), anyMap(), any(MediaType.class), any(byte[].class)))
        .thenReturn(JSON_SAFETY_PREFIX + "{\"status\":\"error\",\"error\":\"foo already exists\"}");
    VerifyException thrown =
        assertThrows(
            VerifyException.class, () -> runCommandForced("-i=" + premiumTermsPath, "-n=foo"));
    assertThat(thrown).hasMessageThat().contains("Server error:");
  }

  @Test
  public void testRun_noInputFileSpecified_throwsException() {
    ParameterException thrown = assertThrows(ParameterException.class, this::runCommand);
    assertThat(thrown).hasMessageThat().contains("The following option is required");
  }

  @Test
  public void testRun_invalidInputData() throws Exception {
    premiumTermsPath =
        writeToNamedTmpFile(
            "tmp_file2",
            loadFile(CreatePremiumListCommandTest.class, "example_invalid_premium_terms.csv"));
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () -> runCommandForced("-i=" + premiumTermsPath, "-n=foo"));
    assertThat(thrown).hasMessageThat().contains("Could not parse line in premium list");
  }
}
