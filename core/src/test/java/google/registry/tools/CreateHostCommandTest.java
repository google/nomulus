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
import static google.registry.testing.DatastoreHelper.createTld;
import static org.junit.Assert.assertThrows;

import com.beust.jcommander.ParameterException;
import org.junit.Test;

/** Unit tests for {@link CreateHostCommand}. */
public class CreateHostCommandTest extends EppToolCommandTestCase<CreateHostCommand> {

  @Test
  public void testSuccess_complete() throws Exception {
    createTld("tld");
    runCommandForced(
        "--client=NewRegistrar",
        "--host=example.tld",
        "--addresses=162.100.102.99,2001:0db8:85a3:0000:0000:8a2e:0370:7334,4.5.6.7");
    eppVerifier.verifySent("host_create_complete.xml");
  }

  @Test
  public void testSuccess_minimal() throws Exception {
    // Test that each optional field can be omitted.
    runCommandForced(
        "--client=NewRegistrar",
        "--host=notours.external");
    eppVerifier.verifySent("host_create_minimal.xml");
  }

  @Test
  public void testFailure_missingHost() {
    assertThrows(ParameterException.class, () -> runCommandForced("--client=NewRegistrar"));
  }

  @Test
  public void testFailure_invalidIpAddress() {
    createTld("tld");
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                runCommandForced(
                    "--client=NewRegistrar", "--host=example.tld", "--addresses=a.b.c.d"));
    assertThat(thrown).hasMessageThat().contains("'a.b.c.d' is not an IP string literal.");
  }
}
