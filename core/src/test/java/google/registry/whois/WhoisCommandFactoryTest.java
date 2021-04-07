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
import static google.registry.testing.DatabaseHelper.newDomainBase;
import static google.registry.testing.DatabaseHelper.newHostResource;
import static google.registry.testing.DatabaseHelper.newRegistry;
import static google.registry.testing.DatabaseHelper.persistNewRegistrar;
import static google.registry.testing.DatabaseHelper.persistResource;

import com.google.common.collect.ImmutableSet;
import com.google.common.net.InternetDomainName;
import google.registry.config.RegistryConfig;
import google.registry.model.domain.DomainBase;
import google.registry.model.host.HostResource;
import google.registry.model.registrar.Registrar;
import google.registry.testing.AppEngineExtension;
import google.registry.testing.DualDatabaseTest;
import google.registry.testing.FakeClock;
import google.registry.testing.TestCacheExtension;
import google.registry.testing.TestOfyAndSql;
import java.net.InetAddress;
import org.joda.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.RegisterExtension;

@DualDatabaseTest
class WhoisCommandFactoryTest {

  FakeClock clock = new FakeClock();

  @RegisterExtension
  final AppEngineExtension appEngine =
      AppEngineExtension.builder().withDatastoreAndCloudSql().withClock(clock).build();

  @RegisterExtension
  final TestCacheExtension testCacheExtension =
      new TestCacheExtension.Builder().withEppResourceCache(Duration.millis(1000000000)).build();

  WhoisCommandFactory noncachedFactory = WhoisCommandFactory.createNonCached();
  WhoisCommandFactory cachedFactory = WhoisCommandFactory.createCached();
  DomainBase domain;
  HostResource host;
  Registrar otherRegistrar;

  int origSingletonCacheRefreshSeconds;

  @BeforeEach
  void setUp() throws Exception {
    persistResource(newRegistry("tld", "TLD"));
    host =
        newHostResource("ns.example.tld")
            .asBuilder()
            .setInetAddresses(ImmutableSet.of(InetAddress.getByName("1.2.3.4")))
            .build();
    persistResource(host);
    domain = newDomainBase("example.tld", host);
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

  @TestOfyAndSql
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
                            .setPersistedCurrentSponsorClientId("OtherRegistrar")
                            .build()));
    response =
        noncachedFactory
            .nameserverLookupByHost(InternetDomainName.from("ns.example.tld"))
            .executeQuery(clock.nowUtc());
    assertThat(response.getResponse(false, "").plainTextOutput())
        .contains("Registrar: OtherRegistrar name");
  }

  @TestOfyAndSql
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
                            .setPersistedCurrentSponsorClientId("OtherRegistrar")
                            .build()));
    response =
        cachedFactory
            .nameserverLookupByHost(InternetDomainName.from("ns.example.tld"))
            .executeQuery(clock.nowUtc());
    assertThat(response.getResponse(false, "").plainTextOutput())
        .contains("Registrar: The Registrar");
  }

  @TestOfyAndSql
  void testNonCached_DomainLookupCommand() throws Exception {
    WhoisResponse response =
        noncachedFactory
            .domainLookup(InternetDomainName.from("example.tld"), true, "REDACTED")
            .executeQuery(clock.nowUtc());
    assertThat(response.getResponse(false, "").plainTextOutput())
        .contains("Registrar: The Registrar");

    tm().transact(
            () ->
                tm().put(
                        domain
                            .asBuilder()
                            .setPersistedCurrentSponsorClientId("OtherRegistrar")
                            .build()));
    response =
        noncachedFactory
            .domainLookup(InternetDomainName.from("example.tld"), true, "REDACTED")
            .executeQuery(clock.nowUtc());
    assertThat(response.getResponse(false, "").plainTextOutput())
        .contains("Registrar: OtherRegistrar name");
  }

  @TestOfyAndSql
  void testCached_DomainLookupCommand() throws Exception {
    WhoisResponse response =
        cachedFactory
            .domainLookup(InternetDomainName.from("example.tld"), true, "REDACTED")
            .executeQuery(clock.nowUtc());
    assertThat(response.getResponse(false, "").plainTextOutput())
        .contains("Registrar: The Registrar");

    tm().transact(
            () ->
                tm().put(
                        domain
                            .asBuilder()
                            .setPersistedCurrentSponsorClientId("OtherRegistrar")
                            .build()));
    response =
        cachedFactory
            .domainLookup(InternetDomainName.from("example.tld"), true, "REDACTED")
            .executeQuery(clock.nowUtc());
    assertThat(response.getResponse(false, "").plainTextOutput())
        .contains("Registrar: The Registrar");
  }

  @TestOfyAndSql
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

  @TestOfyAndSql
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

  @TestOfyAndSql
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
                            .setPersistedCurrentSponsorClientId("OtherRegistrar")
                            .build()));
    response =
        noncachedFactory
            .nameserverLookupByIp(InetAddress.getByName("1.2.3.4"))
            .executeQuery(clock.nowUtc());
    assertThat(response.getResponse(false, "").plainTextOutput())
        .contains("Registrar: OtherRegistrar");
  }
}
