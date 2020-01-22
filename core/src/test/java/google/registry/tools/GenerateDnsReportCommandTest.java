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

import static com.google.common.io.BaseEncoding.base16;
import static com.google.common.truth.Truth.assertThat;
import static google.registry.testing.DatastoreHelper.createTlds;
import static google.registry.testing.DatastoreHelper.newDomainBase;
import static google.registry.testing.DatastoreHelper.newHostResource;
import static google.registry.testing.DatastoreHelper.persistActiveDomain;
import static google.registry.testing.DatastoreHelper.persistActiveHost;
import static google.registry.testing.DatastoreHelper.persistResource;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.joda.time.DateTimeZone.UTC;
import static org.junit.Assert.assertThrows;

import com.beust.jcommander.ParameterException;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.net.InetAddresses;
import com.googlecode.objectify.Key;
import google.registry.model.domain.DomainBase;
import google.registry.model.domain.secdns.DelegationSignerData;
import google.registry.model.eppcommon.StatusValue;
import google.registry.model.host.HostResource;
import google.registry.testing.FakeClock;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import org.joda.time.DateTime;
import org.json.simple.JSONValue;
import org.json.simple.parser.ParseException;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Unit tests for {@link GenerateDnsReportCommand}. */
public class GenerateDnsReportCommandTest extends CommandTestCase<GenerateDnsReportCommand> {

  @Rule
  public final TemporaryFolder folder = new TemporaryFolder();

  private final DateTime now = DateTime.now(UTC);
  private final FakeClock clock = new FakeClock();
  private Path output;

  private Object getOutputAsJson() throws IOException, ParseException {
    try (Reader reader = Files.newBufferedReader(output, UTF_8)) {
      return JSONValue.parseWithException(reader);
    }
  }

  private HostResource nameserver1;
  private HostResource nameserver2;
  private HostResource nameserver3;
  private HostResource nameserver4;
  private DomainBase domain1;

  private static final ImmutableList<?> DS_DATA_OUTPUT = ImmutableList.of(
      ImmutableMap.of(
          "keyTag", 12345L,
          "algorithm", 3L,
          "digestType", 1L,
          "digest", "49FD46E6C4B45C55D4AC"),
      ImmutableMap.of(
          "keyTag", 56789L,
          "algorithm", 2L,
          "digestType", 4L,
          "digest", "69FD46E6C4A45C55D4AC"));

  private static final List<?> DS_DATA_OUTPUT_REVERSED = Lists.reverse(DS_DATA_OUTPUT);

  private static final ImmutableMap<String, ?> DOMAIN1_OUTPUT = ImmutableMap.of(
      "domain", "example.xn--q9jyb4c",
      "nameservers", ImmutableList.of(
          "ns1.example.xn--q9jyb4c",
          "ns2.example.xn--q9jyb4c"),
      "dsData", DS_DATA_OUTPUT);

  // We can't guarantee inner ordering
  private static final ImmutableMap<String, ?> DOMAIN1_OUTPUT_ALT = ImmutableMap.of(
      "domain", "example.xn--q9jyb4c",
      "nameservers", ImmutableList.of(
          "ns1.example.xn--q9jyb4c",
          "ns2.example.xn--q9jyb4c"),
      "dsData", DS_DATA_OUTPUT_REVERSED);

  private static final ImmutableMap<String, ?> DOMAIN2_OUTPUT = ImmutableMap.of(
      "domain", "foobar.xn--q9jyb4c",
      "nameservers", ImmutableList.of(
          "ns1.google.com",
          "ns2.google.com"));

  private static final ImmutableMap<String, ?> NAMESERVER1_OUTPUT = ImmutableMap.of(
      "host", "ns1.example.xn--q9jyb4c",
      "ips", ImmutableList.of(
          "192.168.1.2",
          "2607:f8b0:400d:c00:0:0:0:c0"));

  private static final ImmutableMap<String, ?> NAMESERVER2_OUTPUT = ImmutableMap.of(
      "host", "ns2.example.xn--q9jyb4c",
      "ips", ImmutableList.of(
          "192.168.1.1",
          "2607:f8b0:400d:c00:0:0:0:c1"));

  @Before
  public void init() throws Exception {
    output = Paths.get(folder.newFile().toString());
    command.clock = clock;
    clock.setTo(now);

    createTlds("xn--q9jyb4c", "example");
    nameserver1 = persistResource(
        newHostResource("ns1.example.xn--q9jyb4c")
            .asBuilder()
            .setInetAddresses(ImmutableSet.of(
                InetAddresses.forString("2607:f8b0:400d:c00::c0"),
                InetAddresses.forString("192.168.1.2")))
            .build());
    nameserver2 = persistResource(
        newHostResource("ns2.example.xn--q9jyb4c")
            .asBuilder()
            .setInetAddresses(ImmutableSet.of(
                InetAddresses.forString("192.168.1.1"),
                InetAddresses.forString("2607:f8b0:400d:c00::c1")))
            .build());
    nameserver3 = persistActiveHost("ns1.google.com");
    nameserver4 = persistActiveHost("ns2.google.com");
    domain1 = persistResource(newDomainBase("example.xn--q9jyb4c").asBuilder()
        .setNameservers(ImmutableSet.of(Key.create(nameserver1), Key.create(nameserver2)))
        .setDsData(ImmutableSet.of(
            DelegationSignerData.create(12345, 3, 1, base16().decode("49FD46E6C4B45C55D4AC")),
            DelegationSignerData.create(56789, 2, 4, base16().decode("69FD46E6C4A45C55D4AC"))))
        .build());
    persistResource(newDomainBase("foobar.xn--q9jyb4c").asBuilder()
        .setNameservers(ImmutableSet.of(Key.create(nameserver3), Key.create(nameserver4)))
        .build());
    // Persist a domain in a different tld that should be ignored.
    persistActiveDomain("should-be-ignored.example");
  }

  @Test
  public void testSuccess() throws Exception {
    runCommand("--output=" + output, "--tld=xn--q9jyb4c");
    Iterable<?> output = (Iterable<?>) getOutputAsJson();
    assertThat(output).containsAnyOf(DOMAIN1_OUTPUT, DOMAIN1_OUTPUT_ALT);
    assertThat(output).containsAtLeast(DOMAIN2_OUTPUT, NAMESERVER1_OUTPUT, NAMESERVER2_OUTPUT);
  }

  @Test
  public void testSuccess_skipDeletedDomain() throws Exception {
    persistResource(domain1.asBuilder().setDeletionTime(now).build());
    runCommand("--output=" + output, "--tld=xn--q9jyb4c");
    assertThat((Iterable<?>) getOutputAsJson())
        .containsExactly(DOMAIN2_OUTPUT, NAMESERVER1_OUTPUT, NAMESERVER2_OUTPUT);
  }

  @Test
  public void testSuccess_skipDeletedNameserver() throws Exception {
    persistResource(nameserver1.asBuilder().setDeletionTime(now).build());
    runCommand("--output=" + output, "--tld=xn--q9jyb4c");
    Iterable<?> output = (Iterable<?>) getOutputAsJson();
    assertThat(output).containsAnyOf(DOMAIN1_OUTPUT, DOMAIN1_OUTPUT_ALT);
    assertThat(output).containsAtLeast(DOMAIN2_OUTPUT, NAMESERVER2_OUTPUT);
  }

  @Test
  public void testSuccess_skipClientHoldDomain() throws Exception {
    persistResource(domain1.asBuilder().addStatusValue(StatusValue.CLIENT_HOLD).build());
    runCommand("--output=" + output, "--tld=xn--q9jyb4c");
    assertThat((Iterable<?>) getOutputAsJson())
        .containsExactly(DOMAIN2_OUTPUT, NAMESERVER1_OUTPUT, NAMESERVER2_OUTPUT);
  }

  @Test
  public void testSuccess_skipServerHoldDomain() throws Exception {
    persistResource(domain1.asBuilder().addStatusValue(StatusValue.SERVER_HOLD).build());
    runCommand("--output=" + output, "--tld=xn--q9jyb4c");
    assertThat((Iterable<?>) getOutputAsJson())
        .containsExactly(DOMAIN2_OUTPUT, NAMESERVER1_OUTPUT, NAMESERVER2_OUTPUT);
  }

  @Test
  public void testSuccess_skipPendingDeleteDomain() throws Exception {
    persistResource(
        domain1
            .asBuilder()
            .addStatusValue(StatusValue.PENDING_DELETE)
            .setDeletionTime(now.plusDays(30))
            .build());
    runCommand("--output=" + output, "--tld=xn--q9jyb4c");
    assertThat((Iterable<?>) getOutputAsJson())
        .containsExactly(DOMAIN2_OUTPUT, NAMESERVER1_OUTPUT, NAMESERVER2_OUTPUT);
  }

  @Test
  public void testSuccess_skipDomainsWithoutNameservers() throws Exception {
    persistResource(domain1.asBuilder().setNameservers(ImmutableSet.of()).build());
    runCommand("--output=" + output, "--tld=xn--q9jyb4c");
    assertThat((Iterable<?>) getOutputAsJson())
        .containsExactly(DOMAIN2_OUTPUT, NAMESERVER1_OUTPUT, NAMESERVER2_OUTPUT);
  }

  @Test
  public void testFailure_tldDoesNotExist() {
    assertThrows(IllegalArgumentException.class, () -> runCommand("--tld=foobar"));
  }

  @Test
  public void testFailure_missingTldParameter() {
    assertThrows(ParameterException.class, () -> runCommand(""));
  }
}
