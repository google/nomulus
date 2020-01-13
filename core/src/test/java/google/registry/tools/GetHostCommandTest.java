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
import static google.registry.testing.DatastoreHelper.newHostResource;
import static google.registry.testing.DatastoreHelper.persistActiveHost;
import static google.registry.testing.DatastoreHelper.persistDeletedHost;
import static google.registry.testing.DatastoreHelper.persistResource;
import static org.joda.time.DateTimeZone.UTC;
import static org.junit.Assert.assertThrows;

import com.beust.jcommander.ParameterException;
import org.joda.time.DateTime;
import org.junit.Before;
import org.junit.Test;

/** Unit tests for {@link GetHostCommand}. */
public class GetHostCommandTest extends CommandTestCase<GetHostCommand> {

  DateTime now = DateTime.now(UTC);

  @Before
  public void initialize() {
    createTld("tld");
  }

  @Test
  public void testSuccess() throws Exception {
    persistActiveHost("ns1.example.tld");
    runCommand("ns1.example.tld");
    assertInStdout("fullyQualifiedHostName=ns1.example.tld");
    assertInStdout("Websafe key: agR0ZXN0chgLEgxIb3N0UmVzb3VyY2UiBjItUk9JRAw");
  }

  @Test
  public void testSuccess_expand() throws Exception {
    persistActiveHost("ns1.example.tld");
    runCommand("ns1.example.tld", "--expand");
    assertInStdout("fullyQualifiedHostName=ns1.example.tld");
    assertInStdout("Websafe key: agR0ZXN0chgLEgxIb3N0UmVzb3VyY2UiBjItUk9JRAw");
    assertNotInStdout("LiveRef");
  }

  @Test
  public void testSuccess_multipleArguments() throws Exception {
    persistActiveHost("ns1.example.tld");
    persistActiveHost("ns2.example.tld");
    runCommand("ns1.example.tld", "ns2.example.tld");
    assertInStdout("fullyQualifiedHostName=ns1.example.tld");
    assertInStdout("fullyQualifiedHostName=ns2.example.tld");
    assertInStdout("Websafe key: agR0ZXN0chgLEgxIb3N0UmVzb3VyY2UiBjItUk9JRAw");
    assertInStdout("Websafe key: agR0ZXN0chgLEgxIb3N0UmVzb3VyY2UiBjMtUk9JRAw");
  }

  @Test
  public void testSuccess_multipleTlds() throws Exception {
    persistActiveHost("ns1.example.tld");
    createTld("tld2");
    persistActiveHost("ns1.example.tld2");
    runCommand("ns1.example.tld", "ns1.example.tld2");
    assertInStdout("fullyQualifiedHostName=ns1.example.tld");
    assertInStdout("fullyQualifiedHostName=ns1.example.tld2");
  }

  @Test
  public void testSuccess_deletedHost() throws Exception {
    persistDeletedHost("ns1.example.tld", now.minusDays(1));
    runCommand("ns1.example.tld");
    assertInStdout("Host 'ns1.example.tld' does not exist or is deleted");
  }

  @Test
  public void testSuccess_hostDoesNotExist() throws Exception {
    runCommand("foo.example.tld");
    assertInStdout("Host 'foo.example.tld' does not exist or is deleted");
  }

  @Test
  public void testSuccess_hostDeletedInFuture() throws Exception {
    persistResource(
        newHostResource("ns1.example.tld").asBuilder()
            .setDeletionTime(now.plusDays(1))
            .build());
    runCommand("ns1.example.tld", "--read_timestamp=" + now.plusMonths(1));
    assertInStdout("Host 'ns1.example.tld' does not exist or is deleted");
  }

  @Test
  public void testSuccess_externalHost() throws Exception {
    persistActiveHost("ns1.example.foo");
    runCommand("ns1.example.foo");
    assertInStdout("fullyQualifiedHostName=ns1.example.foo");
  }

  @Test
  public void testFailure_noHostName() {
    assertThrows(ParameterException.class, this::runCommand);
  }
}
