// Copyright 2018 The Nomulus Authors. All Rights Reserved.
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
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import google.registry.tools.server.ToolsTestData;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link NonMutatingEppToolCommand}. */
public class NonMutatingEppToolCommandTest
    extends EppToolCommandTestCase<NonMutatingEppToolCommand> {

  /** Dummy implementation of NonMutatingEppToolCommand. */
  @Parameters(separators = " =", commandDescription = "Dummy NonMutatingEppToolCommand")
  static class TestNonMutatingEppToolCommand extends NonMutatingEppToolCommand {

    @Parameter(names = {"--client"})
    String clientId;

    @Parameter List<String> xmlPayloads;

    @Override
    void initEppToolCommand() {
      for (String xmlData : xmlPayloads) {
        addXmlCommand(clientId, xmlData);
      }
    }
  }

  @Override
  protected NonMutatingEppToolCommand newCommandInstance() {
    return new TestNonMutatingEppToolCommand();
  }

  @Test
  void testSuccess_doesntPrompt_whenNotForced() throws Exception {
    // The choice of xml file is arbitrary.
    runCommand("--client=NewRegistrar", ToolsTestData.loadFile("domain_check.xml"));
    eppVerifier.expectDryRun().verifySent("domain_check.xml");
  }

  @Test
  void testFailure_doesntAllowForce() {
    Exception e =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                runCommand(
                    "--force",
                    "--client=NewRegistrar",
                    ToolsTestData.loadFile("domain_check.xml")));
    assertThat(e).hasMessageThat().contains("--force is unnecessary");
  }
}
