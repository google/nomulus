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
import static google.registry.testing.DatastoreHelper.persistActiveContact;
import static google.registry.testing.DatastoreHelper.persistActiveDomain;
import static google.registry.testing.DatastoreHelper.persistActiveHost;
import static google.registry.testing.DatastoreHelper.persistDeletedContact;
import static google.registry.testing.DatastoreHelper.persistDeletedDomain;
import static google.registry.testing.DatastoreHelper.persistDeletedHost;
import static org.joda.time.DateTimeZone.UTC;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.beust.jcommander.ParameterException;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link GetResourceByKeyCommand}. */
class GetResourceByKeyCommandTest extends CommandTestCase<GetResourceByKeyCommand> {

  private DateTime now = DateTime.now(UTC);

  @BeforeEach
  void beforeEach() {
    createTld("tld");
  }

  @Test
  void testSuccess_domain() throws Exception {
    persistActiveDomain("example.tld");
    runCommand("agR0ZXN0chULEgpEb21haW5CYXNlIgUyLVRMRAw");
    assertInStdout("fullyQualifiedDomainName=example.tld");
    assertInStdout("contact=Key<?>(ContactResource(\"3-ROID\"))");
  }

  @Test
  void testSuccess_domain_expand() throws Exception {
    persistActiveDomain("example.tld");
    runCommand("agR0ZXN0chULEgpEb21haW5CYXNlIgUyLVRMRAw", "--expand");
    assertInStdout("fullyQualifiedDomainName=example.tld");
    assertInStdout("contactId=contact1234");
    assertNotInStdout("LiveRef");
  }

  @Test
  void testSuccess_domain_multipleArguments() throws Exception {
    persistActiveDomain("example.tld");
    persistActiveDomain("example2.tld");
    runCommand(
        "agR0ZXN0chULEgpEb21haW5CYXNlIgUyLVRMRAw", "agR0ZXN0chULEgpEb21haW5CYXNlIgU0LVRMRAw");
    assertInStdout("fullyQualifiedDomainName=example.tld");
    assertInStdout("fullyQualifiedDomainName=example2.tld");
  }

  @Test
  void testFailure_domain_oneDoesNotExist() {
    persistActiveDomain("example.tld");
    NullPointerException thrown =
        assertThrows(
            NullPointerException.class,
            () ->
                runCommand(
                    "agR0ZXN0chULEgpEb21haW5CYXNlIgUyLVRMRAw",
                    "agR0ZXN0chULEgpEb21haW5CYXNlIgU0LVRMRAw"));
    assertThat(thrown)
        .hasMessageThat()
        .contains("Could not load resource for key: Key<?>(DomainBase(\"4-TLD\"))");
  }

  @Test
  void testSuccess_deletedDomain() throws Exception {
    persistDeletedDomain("example.tld", now.minusDays(1));
    runCommand("agR0ZXN0chULEgpEb21haW5CYXNlIgUyLVRMRAw");
    assertInStdout("fullyQualifiedDomainName=example.tld");
    assertInStdout("deletionTime=" + now.minusDays(1));
  }

  @Test
  void testSuccess_contact() throws Exception {
    persistActiveContact("sh8013");
    runCommand("agR0ZXN0chsLEg9Db250YWN0UmVzb3VyY2UiBjItUk9JRAw");
    assertInStdout("contactId=sh8013");
  }

  @Test
  void testSuccess_contact_expand() throws Exception {
    persistActiveContact("sh8013");
    runCommand("agR0ZXN0chsLEg9Db250YWN0UmVzb3VyY2UiBjItUk9JRAw", "--expand");
    assertInStdout("contactId=sh8013");
    assertNotInStdout("LiveRef");
  }

  @Test
  void testSuccess_contact_multipleArguments() throws Exception {
    persistActiveContact("sh8013");
    persistActiveContact("jd1234");
    runCommand(
        "agR0ZXN0chsLEg9Db250YWN0UmVzb3VyY2UiBjItUk9JRAw",
        "agR0ZXN0chsLEg9Db250YWN0UmVzb3VyY2UiBjMtUk9JRAw");
    assertInStdout("contactId=sh8013");
    assertInStdout("contactId=jd1234");
  }

  @Test
  void testFailure_contact_oneDoesNotExist() {
    persistActiveContact("sh8013");
    NullPointerException thrown =
        assertThrows(
            NullPointerException.class,
            () ->
                runCommand(
                    "agR0ZXN0chsLEg9Db250YWN0UmVzb3VyY2UiBjItUk9JRAw",
                    "agR0ZXN0chsLEg9Db250YWN0UmVzb3VyY2UiBjMtUk9JRAw"));
    assertThat(thrown)
        .hasMessageThat()
        .contains("Could not load resource for key: Key<?>(ContactResource(\"3-ROID\"))");
  }

  @Test
  void testSuccess_deletedContact() throws Exception {
    persistDeletedContact("sh8013", now.minusDays(1));
    runCommand("agR0ZXN0chsLEg9Db250YWN0UmVzb3VyY2UiBjItUk9JRAw");
    assertInStdout("contactId=sh8013");
    assertInStdout("deletionTime=" + now.minusDays(1));
  }

  @Test
  void testSuccess_host() throws Exception {
    persistActiveHost("ns1.example.tld");
    runCommand("agR0ZXN0chgLEgxIb3N0UmVzb3VyY2UiBjItUk9JRAw");
    assertInStdout("fullyQualifiedHostName=ns1.example.tld");
  }

  @Test
  void testSuccess_host_expand() throws Exception {
    persistActiveHost("ns1.example.tld");
    runCommand("agR0ZXN0chgLEgxIb3N0UmVzb3VyY2UiBjItUk9JRAw", "--expand");
    assertInStdout("fullyQualifiedHostName=ns1.example.tld");
    assertNotInStdout("LiveRef");
  }

  @Test
  void testSuccess_host_multipleArguments() throws Exception {
    persistActiveHost("ns1.example.tld");
    persistActiveHost("ns2.example.tld");
    runCommand(
        "agR0ZXN0chgLEgxIb3N0UmVzb3VyY2UiBjItUk9JRAw",
        "agR0ZXN0chgLEgxIb3N0UmVzb3VyY2UiBjMtUk9JRAw");
    assertInStdout("fullyQualifiedHostName=ns1.example.tld");
    assertInStdout("fullyQualifiedHostName=ns2.example.tld");
  }

  @Test
  void testFailure_host_oneDoesNotExist() {
    persistActiveHost("ns1.example.tld");
    NullPointerException thrown =
        assertThrows(
            NullPointerException.class,
            () ->
                runCommand(
                    "agR0ZXN0chgLEgxIb3N0UmVzb3VyY2UiBjItUk9JRAw",
                    "agR0ZXN0chgLEgxIb3N0UmVzb3VyY2UiBjMtUk9JRAw"));
    assertThat(thrown)
        .hasMessageThat()
        .contains("Could not load resource for key: Key<?>(HostResource(\"3-ROID\"))");
  }

  @Test
  void testSuccess_deletedHost() throws Exception {
    persistDeletedHost("ns1.example.tld", now.minusDays(1));
    runCommand("agR0ZXN0chgLEgxIb3N0UmVzb3VyY2UiBjItUk9JRAw");
    assertInStdout("fullyQualifiedHostName=ns1.example.tld");
    assertInStdout("deletionTime=" + now.minusDays(1));
  }

  @Test
  void testSuccess_mixedTypes() throws Exception {
    persistActiveDomain("example.tld");
    persistActiveContact("sh8013");
    persistActiveHost("ns1.example.tld");
    runCommand(
        "agR0ZXN0chULEgpEb21haW5CYXNlIgUyLVRMRAw",
        "agR0ZXN0chsLEg9Db250YWN0UmVzb3VyY2UiBjQtUk9JRAw",
        "agR0ZXN0chgLEgxIb3N0UmVzb3VyY2UiBjUtUk9JRAw");
    assertInStdout("fullyQualifiedDomainName=example.tld");
    assertInStdout("contactId=sh8013");
    assertInStdout("fullyQualifiedHostName=ns1.example.tld");
  }

  @Test
  void testFailure_keyDoesNotExist() {
    NullPointerException thrown =
        assertThrows(
            NullPointerException.class,
            () -> runCommand("agR0ZXN0chULEgpEb21haW5CYXNlIgUyLVRMRAw"));
    assertThat(thrown)
        .hasMessageThat()
        .contains("Could not load resource for key: Key<?>(DomainBase(\"2-TLD\"))");
  }

  @Test
  void testFailure_nonsenseKey() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class, () -> runCommand("agR0ZXN0chULEgpEb21haW5CYXN"));
    assertThat(thrown).hasMessageThat().contains("Could not parse Reference");
  }

  @Test
  void testFailure_noParameters() {
    assertThrows(ParameterException.class, this::runCommand);
  }
}
