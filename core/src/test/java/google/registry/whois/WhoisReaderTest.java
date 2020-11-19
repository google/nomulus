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

package google.registry.whois;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.testing.DatabaseHelper.createTlds;
import static google.registry.testing.LogsSubject.assertAboutLogs;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.common.flogger.LoggerConfig;
import com.google.common.testing.TestLogHandler;
import google.registry.testing.AppEngineExtension;
import google.registry.testing.FakeClock;
import java.io.StringReader;
import java.util.logging.Level;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link WhoisReader}. */
class WhoisReaderTest {

  @RegisterExtension
  final AppEngineExtension appEngine =
      AppEngineExtension.builder().withDatastoreAndCloudSql().build();

  private final FakeClock clock = new FakeClock();
  private final TestLogHandler testLogHandler = new TestLogHandler();

  @BeforeEach
  void beforeEach() {
    createTlds("tld", "xn--kgbechtv", "1.test");
    LoggerConfig.getConfig(WhoisReader.class).addHandler(testLogHandler);
  }

  @SuppressWarnings({"TypeParameterUnusedInFormals", "unchecked"})
  <T> T readCommand(String commandStr) throws Exception {
    return (T)
        new WhoisReader(new WhoisCommandFactory(), "Please contact registrar")
            .readCommand(new StringReader(commandStr), false, clock.nowUtc());
  }

  void assertLoadsExampleTld(String commandString) throws Exception {
    DomainLookupCommand command = readCommand(commandString);
    assertThat(command.domainOrHostName.toString()).isEqualTo("example.tld");
  }

  void assertLoadsIDN(String commandString) throws Exception {
    DomainLookupCommand command = readCommand(commandString);
    assertThat(command.domainOrHostName.toString()).isEqualTo("xn--mgbh0fb.xn--kgbechtv");
  }

  void assertLoadsExampleNs(String commandString) throws Exception {
    NameserverLookupByHostCommand command = readCommand(commandString);
    assertThat(command.domainOrHostName.toString()).isEqualTo("ns.example.tld");
  }

  void assertLoadsIDNNs(String commandString) throws Exception {
    NameserverLookupByHostCommand command = readCommand(commandString);
    assertThat(command.domainOrHostName.toString()).isEqualTo("ns.xn--mgbh0fb.xn--kgbechtv");
  }

  void assertNsLookup(String commandString, String expectedIpAddress) throws Exception {
    assertThat(
            this.<NameserverLookupByIpCommand>readCommand(commandString).ipAddress.getHostAddress())
        .isEqualTo(expectedIpAddress);
  }

  void assertLoadsRegistrar(String commandString) throws Exception {
    assertThat(this.<RegistrarLookupCommand>readCommand(commandString).registrarName)
        .isEqualTo("Example Registrar, Inc.");
  }

  @Test
  void testRegistrarLookupWithOneToken() throws Exception {
    assertThat(this.<RegistrarLookupCommand>readCommand("Example").registrarName)
        .isEqualTo("Example");
  }

  @Test
  void testDomainLookupWithoutCRLF() throws Exception {
    assertLoadsExampleTld("example.tld");
  }

  @Test
  void testWhitespaceOnDomainLookupWithCommand() throws Exception {
    assertLoadsExampleTld(" \t domain \t \t   example.tld    \r\n");
  }

  @Test
  void testDomainLookup() throws Exception {
    assertLoadsExampleTld("example.tld\r\n");
  }

  @Test
  void testDomainLookupWithCommand() throws Exception {
    assertLoadsExampleTld("domain example.tld\r\n");
  }

  @Test
  void testCaseInsensitiveDomainLookup() throws Exception {
    assertLoadsExampleTld("EXAMPLE.TLD\r\n");
  }

  @Test
  void testCaseInsensitiveDomainLookupWithCommand() throws Exception {
    assertLoadsExampleTld("DOMAIN EXAMPLE.TLD\r\n");
  }

  @Test
  void testIDNULabelDomainLookup() throws Exception {
    assertLoadsIDN("مثال.إختبار\r\n");
  }

  @Test
  void testIDNULabelDomainLookupWithCommand() throws Exception {
    assertLoadsIDN("domain مثال.إختبار\r\n");
  }

  @Test
  void testIDNALabelDomainLookupWithCommand() throws Exception {
    assertLoadsIDN("domain xn--mgbh0fb.xn--kgbechtv\r\n");
  }

  @Test
  void testIDNALabelDomainLookup() throws Exception {
    assertLoadsIDN("xn--mgbh0fb.xn--kgbechtv\r\n");
  }

  @Test
  void testTooManyArgsDomainLookup() {
    assertThrows(WhoisException.class, () -> readCommand("domain example.tld foo.bar"));
  }

  @Test
  void testTooFewArgsDomainLookup() {
    assertThrows(WhoisException.class, () -> readCommand("domain"));
  }

  @Test
  void testIllegalArgDomainLookup() {
    assertThrows(WhoisException.class, () -> readCommand("domain 1.1"));
  }

  @Test
  void testNameserverLookupWithoutCRLF() throws Exception {
    assertLoadsExampleNs("ns.example.tld");
  }

  @Test
  void testWhitespaceOnNameserverLookupWithCommand() throws Exception {
    assertLoadsExampleNs(" \t nameserver \t \t   ns.example.tld    \r\n");
  }

  @Test
  void testNameserverLookup() throws Exception {
    assertLoadsExampleNs("ns.example.tld\r\n");
  }

  @Test
  void testDeepNameserverLookup() throws Exception {
    NameserverLookupByHostCommand command = readCommand("ns.foo.bar.baz.example.tld\r\n");
    assertThat(command.domainOrHostName.toString()).isEqualTo("ns.foo.bar.baz.example.tld");
    assertThat(command.domainOrHostName.toString()).isEqualTo("ns.foo.bar.baz.example.tld");
  }

  @Test
  void testNameserverLookupWithCommand() throws Exception {
    assertLoadsExampleNs("nameserver ns.example.tld\r\n");
  }

  @Test
  void testCaseInsensitiveNameserverLookup() throws Exception {
    assertLoadsExampleNs("NS.EXAMPLE.TLD\r\n");
  }

  @Test
  void testCaseInsensitiveNameserverLookupWithCommand() throws Exception {
    assertLoadsExampleNs("NAMESERVER NS.EXAMPLE.TLD\r\n");
  }

  @Test
  void testIDNULabelNameserverLookup() throws Exception {
    assertLoadsIDNNs("ns.مثال.إختبار\r\n");
  }

  @Test
  void testIDNULabelNameserverLookupWithCommand() throws Exception {
    assertLoadsIDNNs("nameserver ns.مثال.إختبار\r\n");
  }

  @Test
  void testIDNALabelNameserverLookupWithCommand() throws Exception {
    assertLoadsIDNNs("nameserver ns.xn--mgbh0fb.xn--kgbechtv\r\n");
  }

  @Test
  void testIDNALabelNameserverLookup() throws Exception {
    assertLoadsIDNNs("ns.xn--mgbh0fb.xn--kgbechtv\r\n");
  }

  @Test
  void testTooManyArgsNameserverLookup() {
    assertThrows(WhoisException.class, () -> readCommand("nameserver ns.example.tld foo.bar"));
  }

  @Test
  void testTooFewArgsNameserverLookup() {
    assertThrows(WhoisException.class, () -> readCommand("nameserver"));
  }

  @Test
  void testIllegalArgNameserverLookup() {
    assertThrows(WhoisException.class, () -> readCommand("nameserver 1.1"));
  }

  @Test
  void testRegistrarLookup() throws Exception {
    assertLoadsRegistrar("registrar Example Registrar, Inc.");
  }

  @Test
  void testRegistrarLookupCaseInsensitive() throws Exception {
    assertLoadsRegistrar("REGISTRAR Example Registrar, Inc.");
  }

  @Test
  void testRegistrarLookupWhitespace() throws Exception {
    assertLoadsRegistrar("  \t registrar \t  \tExample    Registrar,   Inc.  ");
  }

  @Test
  void testRegistrarLookupByDefault() throws Exception {
    assertLoadsRegistrar("Example Registrar, Inc.");
  }

  @Test
  void testRegistrarLookupOnTLD() throws Exception {
    assertThat(this.<RegistrarLookupCommand>readCommand("com").registrarName).isEqualTo("com");
  }

  @Test
  void testRegistrarLookupNoArgs() {
    assertThrows(WhoisException.class, () -> readCommand("registrar"));
  }

  @Test
  void testNameserverLookupByIp() throws Exception {
    assertNsLookup("43.34.12.213", "43.34.12.213");
  }

  @Test
  void testNameserverLookupByIpv6() throws Exception {
    assertNsLookup("1080:0:0:0:8:800:200c:417a", "1080:0:0:0:8:800:200c:417a");
  }

  @Test
  void testNameserverLookupByCompressedIpv6() throws Exception {
    assertNsLookup("1080::8:800:200c:417a", "1080:0:0:0:8:800:200c:417a");
  }

  @Test
  void testNameserverLookupByNoncanonicalIpv6() throws Exception {
    assertNsLookup("1080:0:0:0:8:800:200C:417A", "1080:0:0:0:8:800:200c:417a");
  }

  @Test
  void testNameserverLookupByBackwardsCompatibleIpv6() throws Exception {
    assertNsLookup("::FFFF:129.144.52.38", "129.144.52.38");
  }

  @Test
  void testNameserverLookupByIpWithCommand() throws Exception {
    assertNsLookup("nameserver 43.34.12.213", "43.34.12.213");
  }

  @Test
  void testNameserverLookupByIpv6WithCommand() throws Exception {
    assertNsLookup("nameserver 1080:0:0:0:8:800:200C:417a", "1080:0:0:0:8:800:200c:417a");
  }

  @Test
  void testNameserverLookupByIpCaseInsenstive() throws Exception {
    assertNsLookup("NAMESERVER 43.34.12.213", "43.34.12.213");
  }

  @Test
  void testNameserverLookupByIpWhitespace() throws Exception {
    assertNsLookup("  \t\t NAMESERVER   \t 43.34.12.213    \r\n", "43.34.12.213");
  }

  @Test
  void testNameserverLookupByIpTooManyArgs() {
    assertThrows(WhoisException.class, () -> readCommand("nameserver 43.34.12.213 43.34.12.213"));
  }

  @Test
  void testMultilevelDomainLookup() throws Exception {
    this.<DomainLookupCommand>readCommand("example.1.test");
  }

  @Test
  void testMultilevelNameserverLookup() throws Exception {
    this.<NameserverLookupByHostCommand>readCommand("ns.example.1.test");
  }

  @Test
  void testDeepMultilevelNameserverLookup() throws Exception {
    this.<NameserverLookupByHostCommand>readCommand("ns.corp.example.1.test");
  }

  @Test
  void testUnconfiguredTld() throws Exception {
    this.<RegistrarLookupCommand>readCommand("example.test");
    this.<RegistrarLookupCommand>readCommand("1.example.test");
    this.<RegistrarLookupCommand>readCommand("ns.example.2.test");
    this.<RegistrarLookupCommand>readCommand("test");
    this.<RegistrarLookupCommand>readCommand("tld");
  }

  @Test
  void testNoArgs() {
    assertThrows(WhoisException.class, () -> readCommand(""));
  }

  @Test
  void testLogsDomainLookupCommand() throws Exception {
    readCommand("domain example.tld");
    assertAboutLogs()
        .that(testLogHandler)
        .hasLogAtLevelWithMessage(
            Level.INFO, "Attempting domain lookup command using domain name example.tld");
  }

  @Test
  void testLogsNameserverLookupCommandWithIpAddress() throws Exception {
    readCommand("nameserver 43.34.12.213");
    assertAboutLogs()
        .that(testLogHandler)
        .hasLogAtLevelWithMessage(
            Level.INFO, "Attempting nameserver lookup command using 43.34.12.213 as an IP address");
  }

  @Test
  void testLogsNameserverLookupCommandWithHostname() throws Exception {
    readCommand("nameserver ns.example.tld");
    assertAboutLogs()
        .that(testLogHandler)
        .hasLogAtLevelWithMessage(
            Level.INFO, "Attempting nameserver lookup command using ns.example.tld as a hostname");
  }

  @Test
  void testLogsRegistrarLookupCommand() throws Exception {
    readCommand("registrar Example Registrar, Inc.");
    assertAboutLogs()
        .that(testLogHandler)
        .hasLogAtLevelWithMessage(
            Level.INFO,
            "Attempting registrar lookup command using registrar Example Registrar, Inc.");
  }

  @Test
  void testLogsSingleArgumentNameserverLookupUsingIpAddress() throws Exception {
    readCommand("43.34.12.213");
    assertAboutLogs()
        .that(testLogHandler)
        .hasLogAtLevelWithMessage(
            Level.INFO, "Attempting nameserver lookup using 43.34.12.213 as an IP address");
  }

  @Test
  void testLogsSingleArgumentRegistrarLookup() throws Exception {
    readCommand("test");
    assertAboutLogs()
        .that(testLogHandler)
        .hasLogAtLevelWithMessage(
            Level.INFO, "Attempting registrar lookup using test as a registrar");
  }

  @Test
  void testLogsSingleArgumentDomainLookup() throws Exception {
    readCommand("example.tld");
    assertAboutLogs()
        .that(testLogHandler)
        .hasLogAtLevelWithMessage(
            Level.INFO, "Attempting domain lookup using example.tld as a domain name");
  }

  @Test
  void testLogsSingleArgumentNameserverLookupUsingHostname() throws Exception {
    readCommand("ns.example.tld");
    assertAboutLogs()
        .that(testLogHandler)
        .hasLogAtLevelWithMessage(
            Level.INFO, "Attempting nameserver lookup using ns.example.tld as a hostname");
  }

  @Test
  void testLogsMultipleArgumentsButNoParticularCommand() throws Exception {
    readCommand("Example Registrar, Inc.");
    assertAboutLogs()
        .that(testLogHandler)
        .hasLogAtLevelWithMessage(
            Level.INFO,
            "Attempting registrar lookup employing Example Registrar, Inc. as a registrar");
  }
}
