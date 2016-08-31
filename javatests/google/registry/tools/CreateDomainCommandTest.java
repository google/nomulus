// Copyright 2016 The Domain Registry Authors. All Rights Reserved.
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

import com.beust.jcommander.ParameterException;
import org.junit.Before;
import org.junit.Test;

/** Unit tests for {@link CreateDomainCommand}. */
public class CreateDomainCommandTest extends EppToolCommandTestCase<CreateDomainCommand> {

  @Before
  public void initCommand() {
    command.passwordGenerator = new FakePasswordGenerator("abcdefghijklmnopqrstuvwxyz");
  }

  @Test
  public void testSuccess_complete() throws Exception {
    runCommandForced(
        "--client=NewRegistrar",
        "--domain=example.tld",
        "--period=1",
        "--nameservers=ns1.zdns.google,ns2.zdns.google,ns3.zdns.google,ns4.zdns.google",
        "--registrant=crr-admin",
        "--admin=crr-admin",
        "--tech=crr-tech",
        "--password=2fooBAR");
    eppVerifier().verifySent("domain_create_complete.xml");
  }

  @Test
  public void testSuccess_minimal() throws Exception {
    // Test that each optional field can be omitted. Also tests the auto-gen password.
    runCommandForced(
        "--client=NewRegistrar",
        "--domain=example.tld",
        "--registrant=crr-admin",
        "--admin=crr-admin",
        "--tech=crr-tech");
    eppVerifier().verifySent("domain_create_minimal.xml");
  }

  @Test
  public void testFailure_missingClientId() throws Exception {
    thrown.expect(ParameterException.class);
    runCommandForced("--domain=example.tld", "--registrant=crr-admin");
  }

  @Test
  public void testFailure_missingDomain() throws Exception {
    thrown.expect(ParameterException.class);
    runCommandForced(
        "--client=NewRegistrar",
        "--registrant=crr-admin",
        "--admin=crr-admin",
        "--tech=crr-tech");
  }

  @Test
  public void testFailure_missingRegistrant() throws Exception {
    thrown.expect(ParameterException.class);
    runCommandForced(
        "--client=NewRegistrar",
        "--domain=example.tld",
        "--admin=crr-admin",
        "--tech=crr-tech");
  }

  @Test
  public void testFailure_tooManyNameServers() throws Exception {
    thrown.expect(IllegalArgumentException.class);
    runCommandForced(
        "--client=NewRegistrar",
        "--domain=example.tld",
        "--registrant=crr-admin",
        "--admin=crr-admin",
        "--tech=crr-tech",
        "--nameservers=ns1.zdns.google,ns2.zdns.google,ns3.zdns.google,ns4.zdns.google",
        "--nameservers=ns5.zdns.google,ns6.zdns.google,ns7.zdns.google,ns8.zdns.google",
        "--nameservers=ns9.zdns.google,ns10.zdns.google,ns11.zdns.google,ns12.zdns.google",
        "--nameservers=ns13.zdns.google,ns14.zdns.google");
  }

  @Test
  public void testFailure_badPeriod() throws Exception {
    thrown.expect(ParameterException.class);
    runCommandForced(
        "--client=NewRegistrar",
        "--domain=example.tld",
        "--registrant=crr-admin",
        "--admin=crr-admin",
        "--tech=crr-tech",
        "--period=x");
  }

  @Test
  public void testFailure_badFax() throws Exception {
    thrown.expect(ParameterException.class);
    runCommandForced("--client=NewRegistrar", "--fax=3");
  }
}
