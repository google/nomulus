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

import static org.junit.Assert.assertThrows;

import com.beust.jcommander.ParameterException;
import google.registry.testing.DeterministicStringGenerator;
import org.junit.Before;
import org.junit.Test;

/** Unit tests for {@link CreateContactCommand}. */
public class CreateContactCommandTest extends EppToolCommandTestCase<CreateContactCommand> {

  @Before
  public void initCommand() {
    command.passwordGenerator = new DeterministicStringGenerator("abcdefghijklmnopqrstuvwxyz");
  }

  @Test
  public void testSuccess_complete() throws Exception {
    runCommandForced(
        "--client=NewRegistrar",
        "--id=sh8013",
        "--name=\"John Doe\"",
        "--org=\"Example Inc.\"",
        "--street=\"123 Example Dr.\"",
        "--street=\"Floor 3\"",
        "--street=\"Suite 100\"",
        "--city=Dulles",
        "--state=VA",
        "--zip=20166-6503",
        "--cc=US",
        "--phone=+1.7035555555",
        "--fax=+1.7035555556",
        "--email=jdoe@example.com",
        "--password=2fooBAR");
    eppVerifier.verifySent("contact_create_complete.xml");
  }

  @Test
  public void testSuccess_minimal() throws Exception {
    // Will never be the case, but tests that each field can be omitted.
    // Also tests the auto-gen password.
    runCommandForced("--client=NewRegistrar");
    eppVerifier.verifySent("contact_create_minimal.xml");
  }

  @Test
  public void testFailure_missingClientId() {
    assertThrows(ParameterException.class, this::runCommandForced);
  }

  @Test
  public void testFailure_tooManyStreetLines() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            runCommandForced(
                "--client=NewRegistrar",
                "--street=\"123 Example Dr.\"",
                "--street=\"Floor 3\"",
                "--street=\"Suite 100\"",
                "--street=\"Office 1\""));
  }

  @Test
  public void testFailure_badPhone() {
    assertThrows(
        ParameterException.class, () -> runCommandForced("--client=NewRegistrar", "--phone=3"));
  }

  @Test
  public void testFailure_badFax() {
    assertThrows(
        ParameterException.class, () -> runCommandForced("--client=NewRegistrar", "--fax=3"));
  }
}
