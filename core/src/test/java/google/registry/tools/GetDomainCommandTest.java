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

import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.persistActiveDomain;
import static google.registry.testing.DatabaseHelper.persistDeletedDomain;
import static google.registry.testing.DatabaseHelper.persistResource;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.beust.jcommander.ParameterException;
import google.registry.testing.DatabaseHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link GetDomainCommand}. */
class GetDomainCommandTest extends CommandTestCase<GetDomainCommand> {

  @BeforeEach
  void beforeEach() {
    createTld("tld");
    command.clock = fakeClock;
  }

  @Test
  void testSuccess() throws Exception {
    persistActiveDomain("example.tld");
    runCommand("example.tld");
    assertInStdout("domainName=example.tld");
    assertInStdout("Websafe key: " + "kind:Domain" + "@sql:rO0ABXQABTItVExE");
  }

  @Test
  void testSuccess_expand() throws Exception {
    persistActiveDomain("example.tld");
    runCommand("example.tld", "--expand");
    assertInStdout("domainName=example.tld");
    assertInStdout("Websafe key: " + "kind:Domain" + "@sql:rO0ABXQABTItVExE");
    assertNotInStdout("LiveRef");
  }

  @Test
  void testSuccess_canonicalizeDomainName() throws Exception {
    createTld("xn--q9jyb4c");
    persistActiveDomain("xn--aualito-txac.xn--q9jyb4c");
    runCommand("çauçalito.みんな", "--expand");
    assertInStdout("domainName=xn--aualito-txac.xn--q9jyb4c");
  }

  @Test
  void testSuccess_multipleArguments() throws Exception {
    persistActiveDomain("example.tld");
    persistActiveDomain("example2.tld");
    runCommand("example.tld", "example2.tld");
    assertInStdout("domainName=example.tld");
    assertInStdout("domainName=example2.tld");
    assertInStdout("Websafe key: kind:Domain@sql:rO0ABXQABTItVExE");
    assertInStdout("Websafe key: kind:Domain@sql:rO0ABXQABTMtVExE");
  }

  @Test
  void testSuccess_domainDeletedInFuture() throws Exception {
    persistResource(
        DatabaseHelper.newDomain("example.tld")
            .asBuilder()
            .setDeletionTime(fakeClock.nowUtc().plusDays(1))
            .build());
    runCommand("example.tld", "--read_timestamp=" + fakeClock.nowUtc().plusMonths(1));
    assertInStdout("Domain 'example.tld' does not exist or is deleted");
  }

  @Test
  void testSuccess_deletedDomain() throws Exception {
    persistDeletedDomain("example.tld", fakeClock.nowUtc().minusDays(1));
    runCommand("example.tld");
    assertInStdout("Domain 'example.tld' does not exist or is deleted");
  }

  @Test
  void testSuccess_domainDoesNotExist() throws Exception {
    runCommand("something.tld");
    assertInStdout("Domain 'something.tld' does not exist or is deleted");
  }

  @Test
  void testFailure_tldDoesNotExist() throws Exception {
    runCommand("example.foo");
    assertInStdout("Domain 'example.foo' does not exist or is deleted");
  }

  @Test
  void testFailure_noDomainName() {
    assertThrows(ParameterException.class, this::runCommand);
  }

  @Test
  void testSuccess_oneDomainDoesNotExist() throws Exception {
    persistActiveDomain("example.tld");
    createTld("com");
    runCommand("example.com", "example.tld");
    assertInStdout("domainName=example.tld");
    assertInStdout("Domain 'example.com' does not exist or is deleted");
  }

  @Test
  void testSuccess_printDeletedDomain() throws Exception {
    persistDeletedDomain("example.tld", fakeClock.nowUtc().minusDays(1));
    runCommand("--show_deleted", "example.tld");
    assertInStdout("domainName=example.tld");
    assertInStdout("Websafe key: kind:Domain@sql:rO0ABXQABTItVExE");
  }

  @Test
  void testSuccess_printsEntireDomainHistory() throws Exception {
    persistActiveDomain("example.tld");
    persistDeletedDomain("example.tld", fakeClock.nowUtc().minusDays(1));
    runCommand("--show_deleted", "example.tld");
    assertInStdout("domainName=example.tld");
    // Active
    assertInStdout("Websafe key: kind:Domain@sql:rO0ABXQABTItVExE");
    // Deleted
    assertInStdout("Websafe key: kind:Domain@sql:rO0ABXQABTMtVExE");
  }
}

