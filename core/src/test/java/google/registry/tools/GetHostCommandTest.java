// Copyright 2025 The Nomulus Authors. All Rights Reserved.
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
import static google.registry.testing.DatabaseHelper.newHost;
import static google.registry.testing.DatabaseHelper.persistActiveHost;
import static google.registry.testing.DatabaseHelper.persistDeletedHost;
import static google.registry.testing.DatabaseHelper.persistResource;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.beust.jcommander.ParameterException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link GetHostCommand}. */
class GetHostCommandTest extends CommandTestCase<GetHostCommand> {

  @BeforeEach
  void beforeEach() {
    createTld("tld");
    command.clock = fakeClock;
  }

  @Test
  void testSuccess() throws Exception {
    persistActiveHost("ns1.example.tld");
    runCommand("ns1.example.tld");
    assertInStdout("hostName=ns1.example.tld");
    assertInStdout("Websafe key: kind:Host@sql:rO0ABXQABjItUk9JRA");
  }

  @Test
  void testSuccess_expand() throws Exception {
    persistActiveHost("ns1.example.tld");
    runCommand("ns1.example.tld", "--expand");
    assertInStdout("hostName=ns1.example.tld");
    assertInStdout("Websafe key: kind:Host@sql:rO0ABXQABjItUk9JRA");
    assertNotInStdout("LiveRef");
  }

  @Test
  void testSuccess_multipleArguments() throws Exception {
    persistActiveHost("ns1.example.tld");
    persistActiveHost("ns2.example.tld");
    runCommand("ns1.example.tld", "ns2.example.tld");
    assertInStdout("hostName=ns1.example.tld");
    assertInStdout("hostName=ns2.example.tld");
    assertInStdout("Websafe key: kind:Host@sql:rO0ABXQABjItUk9JRA");
    assertInStdout("Websafe key: kind:Host@sql:rO0ABXQABjMtUk9JRA");
  }

  @Test
  void testSuccess_multipleTlds() throws Exception {
    persistActiveHost("ns1.example.tld");
    createTld("tld2");
    persistActiveHost("ns1.example.tld2");
    runCommand("ns1.example.tld", "ns1.example.tld2");
    assertInStdout("hostName=ns1.example.tld");
    assertInStdout("hostName=ns1.example.tld2");
  }

  @Test
  void testSuccess_deletedHost() throws Exception {
    persistDeletedHost("ns1.example.tld", fakeClock.nowUtc().minusDays(1));
    runCommand("ns1.example.tld");
    assertInStdout("Host 'ns1.example.tld' does not exist or is deleted");
  }

  @Test
  void testSuccess_hostDoesNotExist() throws Exception {
    runCommand("foo.example.tld");
    assertInStdout("Host 'foo.example.tld' does not exist or is deleted");
  }

  @Test
  void testSuccess_hostDeletedInFuture() throws Exception {
    persistResource(
        newHost("ns1.example.tld")
            .asBuilder()
            .setDeletionTime(fakeClock.nowUtc().plusDays(1))
            .build());
    runCommand("ns1.example.tld", "--read_timestamp=" + fakeClock.nowUtc().plusMonths(1));
    assertInStdout("Host 'ns1.example.tld' does not exist or is deleted");
  }

  @Test
  void testSuccess_externalHost() throws Exception {
    persistActiveHost("ns1.example.foo");
    runCommand("ns1.example.foo");
    assertInStdout("hostName=ns1.example.foo");
  }

  @Test
  void testFailure_noHostName() {
    assertThrows(ParameterException.class, this::runCommand);
  }
}
