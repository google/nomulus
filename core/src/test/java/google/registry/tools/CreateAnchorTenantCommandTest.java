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

import static google.registry.testing.DatastoreHelper.createTld;
import static google.registry.testing.DatastoreHelper.persistPremiumList;
import static google.registry.testing.DatastoreHelper.persistResource;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.beust.jcommander.ParameterException;
import google.registry.model.registry.Registry;
import google.registry.testing.DeterministicStringGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link CreateAnchorTenantCommand}. */
class CreateAnchorTenantCommandTest extends EppToolCommandTestCase<CreateAnchorTenantCommand> {

  @BeforeEach
  void beforeEach() {
    command.passwordGenerator = new DeterministicStringGenerator("abcdefghijklmnopqrstuvwxyz");
  }

  @Test
  void testSuccess() throws Exception {
    runCommandForced("--client=NewRegistrar", "--superuser",
        "--reason=anchor-tenant-test", "--contact=jd1234", "--domain_name=example.tld");
    eppVerifier.expectSuperuser().verifySent("domain_create_anchor_tenant.xml");
  }

  @Test
  void testSuccess_suppliedPassword() throws Exception {
    runCommandForced("--client=NewRegistrar", "--superuser", "--password=foo",
        "--reason=anchor-tenant-test", "--contact=jd1234", "--domain_name=example.tld");
    eppVerifier.expectSuperuser().verifySent("domain_create_anchor_tenant_password.xml");
  }

  @Test
  void testSuccess_multipleWordReason() throws Exception {
    runCommandForced("--client=NewRegistrar", "--superuser",
        "--reason=\"anchor tenant test\"", "--contact=jd1234", "--domain_name=example.tld");
    eppVerifier
        .expectSuperuser()
        .verifySent("domain_create_anchor_tenant_multiple_word_reason.xml");
  }

  @Test
  void testSuccess_noReason() throws Exception {
    runCommandForced("--client=NewRegistrar", "--superuser",
        "--contact=jd1234", "--domain_name=example.tld");
    eppVerifier.expectSuperuser().verifySent("domain_create_anchor_tenant_no_reason.xml");
  }

  @Test
  void testSuccess_feeStandard() throws Exception {
    runCommandForced("--client=NewRegistrar", "--superuser", "--fee",
        "--reason=anchor-tenant-test", "--contact=jd1234", "--domain_name=example.tld");
    eppVerifier.expectSuperuser().verifySent("domain_create_anchor_tenant_fee_standard.xml");
  }

  @Test
  void testSuccess_feePremium() throws Exception {
    createTld("tld");
    persistResource(
        Registry.get("tld")
            .asBuilder()
            .setPremiumList(persistPremiumList("tld", "premium,JPY 20000"))
            .build());
    runCommandForced("--client=NewRegistrar", "--superuser", "--fee",
        "--reason=anchor-tenant-test", "--contact=jd1234", "--domain_name=premium.tld");
    eppVerifier.expectSuperuser().verifySent("domain_create_anchor_tenant_fee_premium.xml");
  }

  @Test
  void testFailure_mainParameter() {
    assertThrows(
        ParameterException.class,
        () ->
            runCommandForced(
                "--tld=tld",
                "--client=NewRegistrar",
                "--superuser",
                "--reason=anchor-tenant-test",
                "--contact=jd1234",
                "--domain_name=example.tld",
                "foo"));
  }

  @Test
  void testFailure_missingClientId() {
    assertThrows(
        ParameterException.class,
        () ->
            runCommandForced(
                "--superuser",
                "--reason=anchor-tenant-test",
                "--contact=jd1234",
                "--domain_name=example.tld"));
  }

  @Test
  void testFailure_unknownFlag() {
    assertThrows(
        ParameterException.class,
        () ->
            runCommandForced(
                "--foo=bar",
                "--client=NewRegistrar",
                "--superuser",
                "--reason=anchor-tenant-test",
                "--contact=jd1234",
                "--domain_name=example.tld"));
  }

  @Test
  void testFailure_missingDomainName() {
    assertThrows(
        ParameterException.class,
        () ->
            runCommandForced(
                "--client=NewRegistrar",
                "--superuser",
                "--reason=anchor-tenant-test",
                "--contact=jd1234",
                "foo"));
  }

  @Test
  void testFailure_missingContact() {
    assertThrows(
        ParameterException.class,
        () ->
            runCommandForced(
                "--client=NewRegistrar",
                "--superuser",
                "--reason=anchor-tenant-test",
                "--domain_name=example.tld",
                "foo"));
  }

  @Test
  void testFailure_notAsSuperuser() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            runCommandForced(
                "--client=NewRegistrar",
                "--reason=anchor-tenant-test",
                "--contact=jd1234",
                "--domain_name=example.tld"));
  }
}
