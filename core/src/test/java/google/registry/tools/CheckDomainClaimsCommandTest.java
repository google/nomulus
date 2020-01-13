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

import static google.registry.testing.DatastoreHelper.persistNewRegistrar;
import static org.junit.Assert.assertThrows;

import com.beust.jcommander.ParameterException;
import google.registry.model.registrar.Registrar.Type;
import org.junit.Before;
import org.junit.Test;

/** Unit tests for {@link CheckDomainClaimsCommand}. */
public class CheckDomainClaimsCommandTest extends EppToolCommandTestCase<CheckDomainClaimsCommand> {

  @Before
  public void before() {
    persistNewRegistrar("adminreg", "Admin Registrar", Type.REAL, 693L);
    command.registryAdminClientId = "adminreg";
  }

  @Test
  public void testSuccess() throws Exception {
    runCommand("--client=NewRegistrar", "example.tld");
    eppVerifier.expectDryRun().verifySent("domain_check_claims.xml");
  }

  @Test
  public void testSuccess_multipleTlds() throws Exception {
    runCommand("--client=NewRegistrar", "example.tld", "example.tld2");
    eppVerifier
        .expectDryRun()
        .verifySent("domain_check_claims.xml")
        .verifySent("domain_check_claims_second_tld.xml");
  }

  @Test
  public void testSuccess_multipleDomains() throws Exception {
    runCommand(
        "--client=NewRegistrar",
        "example.tld",
        "example2.tld",
        "example3.tld");
    eppVerifier.expectDryRun().verifySent("domain_check_claims_multiple.xml");
  }

  @Test
  public void testSuccess_multipleDomainsAndTlds() throws Exception {
    runCommand(
        "--client=NewRegistrar",
        "example.tld",
        "example2.tld",
        "example3.tld",
        "example.tld2");
    eppVerifier
        .expectDryRun()
        .verifySent("domain_check_claims_multiple.xml")
        .verifySent("domain_check_claims_second_tld.xml");
  }

  @Test
  public void testSuccess_unspecifiedClientId_defaultsToRegistryRegistrar() throws Exception {
    runCommand("example.tld");
    eppVerifier.expectDryRun().expectClientId("adminreg").verifySent("domain_check_claims.xml");
  }

  @Test
  public void testFailure_NoMainParameter() {
    assertThrows(ParameterException.class, () -> runCommand("--client=NewRegistrar"));
  }

  @Test
  public void testFailure_unknownFlag() {
    assertThrows(
        ParameterException.class,
        () -> runCommand("--client=NewRegistrar", "--unrecognized=foo", "example.tld"));
  }
}
