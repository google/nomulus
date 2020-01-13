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

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertThrows;

import com.beust.jcommander.ParameterException;
import google.registry.tools.server.ToolsTestData;
import java.io.ByteArrayInputStream;
import org.junit.Before;
import org.junit.Test;

/** Unit tests for {@link ExecuteEppCommand}. */
public class ExecuteEppCommandTest extends EppToolCommandTestCase<ExecuteEppCommand> {

  private String xmlInput;
  private String eppFile;

  @Before
  public void initCommand() throws Exception {
    xmlInput = ToolsTestData.loadFile("contact_create.xml");
    eppFile = writeToNamedTmpFile("eppFile", xmlInput);
  }

  @Test
  public void testSuccess() throws Exception {
    runCommand("--client=NewRegistrar", "--force", eppFile);
    eppVerifier.verifySent("contact_create.xml");
  }

  @Test
  public void testSuccess_dryRun() throws Exception {
    runCommand("--client=NewRegistrar", "--dry_run", eppFile);
    eppVerifier.expectDryRun().verifySent("contact_create.xml");
  }

  @Test
  public void testSuccess_withSuperuser() throws Exception {
    runCommand("--client=NewRegistrar", "--superuser", "--force", eppFile);
    eppVerifier.expectSuperuser().verifySent("contact_create.xml");
  }

  @Test
  public void testSuccess_fromStdin() throws Exception {
    System.setIn(new ByteArrayInputStream(xmlInput.getBytes(UTF_8)));
    runCommand("--client=NewRegistrar", "--force");
    eppVerifier.verifySent("contact_create.xml");
  }

  @Test
  public void testSuccess_multipleFiles() throws Exception {
    String xmlInput2 = ToolsTestData.loadFile("domain_check.xml");
    String eppFile2 = writeToNamedTmpFile("eppFile2", xmlInput2);
    runCommand("--client=NewRegistrar", "--force", eppFile, eppFile2);
    eppVerifier
        .verifySent("contact_create.xml")
        .verifySent("domain_check.xml");
  }

  @Test
  public void testFailure_missingClientId() {
    assertThrows(ParameterException.class, () -> runCommand("--force", "foo.xml"));
  }

  @Test
  public void testFailure_forceAndDryRunIncompatible() {
    assertThrows(
        IllegalArgumentException.class,
        () -> runCommand("--client=NewRegistrar", "--force", "--dry_run", eppFile));
  }

  @Test
  public void testFailure_unknownFlag() {
    assertThrows(
        ParameterException.class,
        () -> runCommand("--client=NewRegistrar", "--unrecognized=foo", "--force", "foo.xml"));
  }
}
