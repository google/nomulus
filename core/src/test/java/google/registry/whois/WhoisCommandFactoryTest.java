// Copyright 2021 The Nomulus Authors. All Rights Reserved.
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
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.testing.DatabaseHelper.newHost;
import static google.registry.testing.DatabaseHelper.newTld;
import static google.registry.testing.DatabaseHelper.persistNewRegistrar;
import static google.registry.testing.DatabaseHelper.persistResource;

import com.google.common.collect.ImmutableSet;
import com.google.common.net.InternetDomainName;
import google.registry.config.RegistryConfig;
import google.registry.model.domain.Domain;
import google.registry.model.host.Host;
import google.registry.model.registrar.Registrar;
import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.persistence.transaction.JpaTestExtensions.JpaIntegrationTestExtension;
import google.registry.testing.DatabaseHelper;
import google.registry.testing.FakeClock;
import google.registry.testing.TestCacheExtension;
import java.net.InetAddress;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

class WhoisCommandFactoryTest {

  FakeClock clock = new FakeClock();

  @RegisterExtension
  final JpaIntegrationTestExtension jpa =
      new JpaTestExtensions.Builder().withClock(clock).buildIntegrationTestExtension();

  @RegisterExtension
  final TestCacheExtension testCacheExtension =
      new TestCacheExtension.Builder().withForeignKeyResourceCache(Duration.ofDays(1)).build();

  WhoisCommandFactory noncachedFactory = WhoisCommandFactory.createNonCached();
  WhoisCommandFactory cachedFactory = WhoisCommandFactory.createCached();
  Domain domain;
  Host host;
  Registrar otherRegistrar;

  int origSingletonCacheRefreshSeconds;

  @BeforeEach
  void setUp() throws Exception {
    persistResource(newTld("tld", "TLD"));
    host =
        newHost("ns.example.tld")
            .asBuilder()
            .setInetAddresses(ImmutableSet.of(InetAddress.getByName("1.2.3.4")))
            .build();
    persistResource(host);
    domain = DatabaseHelper.newDomain("example.tld", host);
    persistResource(domain);
    otherRegistrar = persistNewRegistrar("OtherRegistrar");
    otherRegistrar =
        persistResource(
            otherRegistrar
                .asBuilder()
                .setState(Registrar.State.ACTIVE)
                .setPhoneNumber("+1.2223334444")
                .build());

    // In addition to the TestCacheExtension, we have to set a long singleton cache timeout.
    RegistryConfig.CONFIG_SETTINGS.get().caching.singletonCacheRefreshSeconds = 1000000;
  }

  @AfterEach
  void tearDown() {
    // Restore the singleton cache timeout.  For some reason, this doesn't work if we store the
    // original value in an instance variable (I suspect there may be some overlap in test
    // execution) so just restore to zero.
    RegistryConfig.CONFIG_SETTINGS.get().caching.singletonCacheRefreshSeconds = 0;
  }

  @Test
  void testNonCached_NameserverLookupByHostCommand() throws Exception {
    WhoisResponse response =
        noncachedFactory
            .nameserverLookupByHost(InternetDomainName.from("ns.example.tld"))
            .executeQuery(clock.nowUtc());
    assertThat(response.getResponse(false, "").plainTextOutput())
        .contains("Registrar: The Registrar");

    // Note that we can't use persistResource() for these as that clears the cache.
    tm().transact(
            () ->
                tm().put(
                        host.asBuilder()
                            .setPersistedCurrentSponsorRegistrarId("OtherRegistrar")
                            .build()));
    response =
        noncachedFactory
            .nameserverLookupByHost(InternetDomainName.from("ns.example.tld"))
            .executeQuery(clock.nowUtc());
    assertThat(response.getResponse(false, "").plainTextOutput())
        .contains("Registrar: OtherRegistrar name");
  }

  @Test
  void testCached_NameserverLookupByHostCommand() throws Exception {
    WhoisResponse response =
        cachedFactory
            .nameserverLookupByHost(InternetDomainName.from("ns.example.tld"))
            .executeQuery(clock.nowUtc());
    assertThat(response.getResponse(false, "").plainTextOutput())
        .contains("Registrar: The Registrar");

    tm().transact(
            () ->
                tm().put(
                        host.asBuilder()
                            .setPersistedCurrentSponsorRegistrarId("OtherRegistrar")
                            .build()));
    response =
        cachedFactory
            .nameserverLookupByHost(InternetDomainName.from("ns.example.tld"))
            .executeQuery(clock.nowUtc());
    assertThat(response.getResponse(false, "").plainTextOutput())
        .contains("Registrar: The Registrar");
  }

  @Test
  void testNonCached_DomainLookupCommand() throws Exception {
    WhoisResponse response =
        noncachedFactory
            .domainLookup(
                InternetDomainName.from("example.tld"), true, "REDACTED", "Blocked by BSA")
            .executeQuery(clock.nowUtc());
    assertThat(response.getResponse(false, "").plainTextOutput())
        .contains("Registrar: The Registrar");

    tm().transact(
            () ->
                tm().put(
                        domain
                            .asBuilder()
                            .setPersistedCurrentSponsorRegistrarId("OtherRegistrar")
                            .build()));
    response =
        noncachedFactory
            .domainLookup(
                InternetDomainName.from("example.tld"), true, "REDACTED", "Blocked by BSA")
            .executeQuery(clock.nowUtc());
    assertThat(response.getResponse(false, "").plainTextOutput())
        .contains("Registrar: OtherRegistrar name");
  }

  @Test
  void testCached_DomainLookupCommand() throws Exception {
    WhoisResponse response =
        cachedFactory
            .domainLookup(InternetDomainName.from("example.tld"), true, "REDACTED", "Not tested")
            .executeQuery(clock.nowUtc());
    assertThat(response.getResponse(false, "").plainTextOutput())
        .contains("Registrar: The Registrar");

    tm().transact(
            () ->
                tm().put(
                        domain
                            .asBuilder()
                            .setPersistedCurrentSponsorRegistrarId("OtherRegistrar")
                            .build()));
    response =
        cachedFactory
            .domainLookup(
                InternetDomainName.from("example.tld"), true, "REDACTED", "Blocked by BSA")
            .executeQuery(clock.nowUtc());
    assertThat(response.getResponse(false, "").plainTextOutput())
        .contains("Registrar: The Registrar");
  }

  @Test
  void testNonCached_RegistrarLookupCommand() throws Exception {
    WhoisResponse response =
        noncachedFactory.registrarLookup("OtherRegistrar").executeQuery(clock.nowUtc());
    assertThat(response.getResponse(false, "").plainTextOutput())
        .contains("Phone Number: +1.2223334444");

    tm().transact(
            () -> tm().put(otherRegistrar.asBuilder().setPhoneNumber("+1.2345677890").build()));
    response = noncachedFactory.registrarLookup("OtherRegistrar").executeQuery(clock.nowUtc());
    assertThat(response.getResponse(false, "").plainTextOutput())
        .contains("Phone Number: +1.2345677890");
  }

  @Test
  void testCached_RegistrarLookupCommand() throws Exception {
    WhoisResponse response =
        cachedFactory.registrarLookup("OtherRegistrar").executeQuery(clock.nowUtc());
    assertThat(response.getResponse(false, "").plainTextOutput())
        .contains("Phone Number: +1.2223334444");

    tm().transact(
            () -> tm().put(otherRegistrar.asBuilder().setPhoneNumber("+1.2345677890").build()));
    response = cachedFactory.registrarLookup("OtherRegistrar").executeQuery(clock.nowUtc());
    assertThat(response.getResponse(false, "").plainTextOutput())
        .contains("Phone Number: +1.2223334444");
  }

  @Test
  void testNonCached_NameserverLookupByIpCommand() throws Exception {
    // Note that this lookup currently doesn't cache the hosts, so there's no point in testing the
    // "cached" case.  This test is here so that it will fail if anyone adds caching.
    WhoisResponse response =
        noncachedFactory
            .nameserverLookupByIp(InetAddress.getByName("1.2.3.4"))
            .executeQuery(clock.nowUtc());
    assertThat(response.getResponse(false, "").plainTextOutput())
        .contains("Registrar: The Registrar");

    tm().transact(
            () ->
                tm().put(
                        host.asBuilder()
                            .setPersistedCurrentSponsorRegistrarId("OtherRegistrar")
                            .build()));
    response =
        noncachedFactory
            .nameserverLookupByIp(InetAddress.getByName("1.2.3.4"))
            .executeQuery(clock.nowUtc());
    assertThat(response.getResponse(false, "").plainTextOutput())
        .contains("Registrar: OtherRegistrar");
  }
}
