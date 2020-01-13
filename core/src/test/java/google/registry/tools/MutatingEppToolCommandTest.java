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
import static org.junit.Assert.assertThrows;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import google.registry.tools.server.ToolsTestData;
import java.util.List;
import org.junit.Test;

/** Unit tests for {@link MutatingEppToolCommand}. */
public class MutatingEppToolCommandTest extends EppToolCommandTestCase<MutatingEppToolCommand> {

  /** Dummy implementation of MutatingEppToolCommand. */
  @Parameters(separators = " =", commandDescription = "Dummy MutatingEppToolCommand")
  static class TestMutatingEppToolCommand extends MutatingEppToolCommand {

    @Parameter(names = {"--client"})
    String clientId;

    @Parameter
    List<String> xmlPayloads;

    @Override
    protected void initMutatingEppToolCommand() {
      for (String xmlData : xmlPayloads) {
        addXmlCommand(clientId, xmlData);
      }
    }
  }

  @Override
  protected MutatingEppToolCommand newCommandInstance() {
    return new TestMutatingEppToolCommand();
  }

  @Test
  public void testSuccess_dryrun() throws Exception {
    // The choice of xml file is arbitrary.
    runCommand(
        "--client=NewRegistrar",
        "--dry_run",
        ToolsTestData.loadFile("contact_create.xml"));
    eppVerifier.expectDryRun().verifySent("contact_create.xml");
  }

  @Test
  public void testFailure_cantUseForceWithDryRun() {
    Exception e =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                runCommand(
                    "--force",
                    "--dry_run",
                    "--client=NewRegistrar",
                    ToolsTestData.loadFile("domain_check.xml")));
    assertThat(e).hasMessageThat().isEqualTo("--force and --dry_run are incompatible");
  }
}
