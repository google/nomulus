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
import static google.registry.bsa.persistence.BsaTestingUtils.persistBsaLabel;
import static google.registry.model.EppResourceUtils.loadByForeignKeyByCacheIfEnabled;
import static google.registry.model.registrar.Registrar.State.ACTIVE;
import static google.registry.model.registrar.Registrar.Type.PDT;
import static google.registry.model.tld.Tlds.getTlds;
import static google.registry.testing.DatabaseHelper.createTlds;
import static google.registry.testing.DatabaseHelper.loadRegistrar;
import static google.registry.testing.DatabaseHelper.persistActiveDomain;
import static google.registry.testing.DatabaseHelper.persistResource;
import static google.registry.testing.DatabaseHelper.persistResources;
import static google.registry.testing.FullFieldsTestEntityHelper.makeDomain;
import static google.registry.testing.FullFieldsTestEntityHelper.makeRegistrar;
import static google.registry.testing.FullFieldsTestEntityHelper.makeRegistrarPocs;
import static google.registry.whois.WhoisTestData.loadFile;
import static jakarta.servlet.http.HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
import static jakarta.servlet.http.HttpServletResponse.SC_NOT_FOUND;
import static jakarta.servlet.http.HttpServletResponse.SC_OK;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableSet;
import com.google.common.net.InetAddresses;
import google.registry.model.contact.Contact;
import google.registry.model.domain.Domain;
import google.registry.model.eppcommon.Trid;
import google.registry.model.host.Host;
import google.registry.model.registrar.Registrar;
import google.registry.model.tld.Tld;
import google.registry.model.transfer.DomainTransferData;
import google.registry.model.transfer.TransferStatus;
import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.persistence.transaction.JpaTestExtensions.JpaIntegrationTestExtension;
import google.registry.testing.FakeClock;
import google.registry.testing.FakeResponse;
import google.registry.testing.FakeSleeper;
import google.registry.testing.FullFieldsTestEntityHelper;
import google.registry.testing.TestCacheExtension;
import google.registry.util.Retrier;
import google.registry.whois.WhoisMetrics.WhoisMetric;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.time.Duration;
import java.util.Optional;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link WhoisAction}. */
public class WhoisActionTest {

  private final FakeClock clock = new FakeClock(DateTime.parse("2009-06-29T20:13:00Z"));

  @RegisterExtension
  final JpaIntegrationTestExtension jpa =
      new JpaTestExtensions.Builder().withClock(clock).buildIntegrationTestExtension();

  @RegisterExtension
  public final TestCacheExtension testCacheExtension =
      new TestCacheExtension.Builder()
          .withEppResourceCache(Duration.ofDays(1))
          .withForeignKeyCache(Duration.ofDays(1))
          .build();

  private final FakeResponse response = new FakeResponse();

  private WhoisAction newWhoisAction(String input) {
    WhoisAction whoisAction = new WhoisAction();
    whoisAction.clock = clock;
    whoisAction.input = new StringReader(input);
    whoisAction.response = response;
    whoisAction.whoisReader =
        new WhoisReader(
            WhoisCommandFactory.createCached(), "Please contact registrar", "Blocked by BSA: %s");
    whoisAction.whoisMetrics = new WhoisMetrics();
    whoisAction.metricBuilder = WhoisMetric.builderForRequest(clock);
    whoisAction.disclaimer =
        "Doodle Disclaimer\nI exist so that carriage return\nin disclaimer can be tested.";
    whoisAction.retrier = new Retrier(new FakeSleeper(clock), 3);
    return whoisAction;
  }

  @BeforeEach
  void setUp() {
    createTlds("lol", "xn--q9jyb4c", "1.test");
  }

  @Test
  void testRun_badRequest_stillSends200() {
    newWhoisAction("\r\n").run();
    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(response.getPayload()).isEqualTo(loadFile("whois_action_no_command.txt"));
  }

  private static Domain makeDomainWithRegistrar(Registrar registrar) {
    return makeDomain(
        "cat.lol",
        persistResource(
            FullFieldsTestEntityHelper.makeContact("5372808-ERL", "Goblin Market", "lol@cat.lol")),
        persistResource(
            FullFieldsTestEntityHelper.makeContact("5372808-IRL", "Santa Claus", "BOFH@cat.lol")),
        persistResource(
            FullFieldsTestEntityHelper.makeContact("5372808-TRL", "The Raven", "bog@cat.lol")),
        persistResource(FullFieldsTestEntityHelper.makeHost("ns1.cat.lol", "1.2.3.4")),
        persistResource(
            FullFieldsTestEntityHelper.makeHost("ns2.cat.lol", "bad:f00d:cafe::15:beef")),
        registrar);
  }

  @Test
  void testRun_domainQuery_works() {
    Registrar registrar =
        persistResource(makeRegistrar("evilregistrar", "Yes Virginia", ACTIVE));
    persistResource(makeDomainWithRegistrar(registrar));
    persistResources(makeRegistrarPocs(registrar));
    newWhoisAction("domain cat.lol\r\n").run();
    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(response.getPayload()).isEqualTo(loadFile("whois_action_domain.txt"));
  }

  @Test
  void testRun_domainQuery_usesCache() {
    Registrar registrar =
        persistResource(makeRegistrar("evilregistrar", "Yes Virginia", ACTIVE));
    persistResource(makeDomainWithRegistrar(registrar));
    persistResources(makeRegistrarPocs(registrar));
    // Populate the cache for both the domain and contact.
    Domain domain = loadByForeignKeyByCacheIfEnabled(Domain.class, "cat.lol", clock.nowUtc()).get();
    Contact contact =
        loadByForeignKeyByCacheIfEnabled(Contact.class, "5372808-ERL", clock.nowUtc()).get();
    // Make a change to the domain and contact that won't be seen because the cache will be hit.
    persistResource(domain.asBuilder().setDeletionTime(clock.nowUtc().minusDays(1)).build());
    persistResource(
        contact
            .asBuilder()
            .setInternationalizedPostalInfo(
                contact
                    .getInternationalizedPostalInfo()
                    .asBuilder()
                    .setOrg("Two by Two, Hands Blue Inc.")
                    .build())
            .build());
    newWhoisAction("domain cat.lol\r\n").run();
    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(response.getPayload()).isEqualTo(loadFile("whois_action_domain.txt"));
  }

  @Test
  void testRun_domainQuery_registeredDomainUnaffectedByBsa() {
    persistResource(
        Tld.get("lol")
            .asBuilder()
            .setBsaEnrollStartTime(Optional.of(clock.nowUtc().minusDays(1)))
            .build());
    persistBsaLabel("cat");

    Registrar registrar = persistResource(makeRegistrar("evilregistrar", "Yes Virginia", ACTIVE));
    persistResource(makeDomainWithRegistrar(registrar));
    persistResources(makeRegistrarPocs(registrar));
    newWhoisAction("domain cat.lol\r\n").run();
    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(response.getPayload()).isEqualTo(loadFile("whois_action_domain.txt"));
  }

  @Test
  void testRun_domainQuery_unregisteredDomainShowBsaMessage() {
    persistResource(
        Tld.get("lol")
            .asBuilder()
            .setBsaEnrollStartTime(Optional.of(clock.nowUtc().minusDays(1)))
            .build());
    persistBsaLabel("cat");

    newWhoisAction("domain cat.lol\r\n").run();
    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(response.getPayload()).isEqualTo(loadFile("whois_action_domain_blocked_by_bsa.txt"));
  }

  @Test
  void testRun_domainAfterTransfer_hasUpdatedEppTimeAndClientId() {
    Registrar registrar = persistResource(makeRegistrar("TheRegistrar", "Yes Virginia", ACTIVE));
    persistResource(
        makeDomainWithRegistrar(registrar)
            .asBuilder()
            .setTransferData(
                new DomainTransferData.Builder()
                    .setGainingRegistrarId("TheRegistrar")
                    .setLosingRegistrarId("NewRegistrar")
                    .setTransferRequestTime(DateTime.parse("2009-05-29T20:13:00Z"))
                    .setPendingTransferExpirationTime(DateTime.parse("2010-03-01T00:00:00Z"))
                    .setTransferStatus(TransferStatus.PENDING)
                    .setTransferRequestTrid(Trid.create("client-trid", "server-trid"))
                    .build())
            .build());
    persistResources(makeRegistrarPocs(registrar));
    clock.setTo(DateTime.parse("2011-01-01T00:00:00Z"));

    newWhoisAction("domain cat.lol\r\n").run();

    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(response.getPayload()).isEqualTo(loadFile("whois_action_transferred_domain.txt"));
  }

  @Test
  void testRun_idnDomain_works() {
    Registrar registrar = persistResource(makeRegistrar(
        "evilregistrar", "Yes Virginia", ACTIVE));
    persistResource(
        makeDomain(
            "cat.みんな",
            persistResource(
                FullFieldsTestEntityHelper.makeContact("5372808-ERL", "(◕‿◕)", "lol@cat.みんな")),
            persistResource(
                FullFieldsTestEntityHelper.makeContact(
                    "5372808-IRL", "Santa Claus", "BOFH@cat.みんな")),
            persistResource(
                FullFieldsTestEntityHelper.makeContact("5372808-TRL", "The Raven", "bog@cat.みんな")),
            persistResource(FullFieldsTestEntityHelper.makeHost("ns1.cat.みんな", "1.2.3.4")),
            persistResource(
                FullFieldsTestEntityHelper.makeHost("ns2.cat.みんな", "bad:f00d:cafe::15:beef")),
            registrar));
    persistResources(makeRegistrarPocs(registrar));
    newWhoisAction("domain cat.みんな\r\n").run();
    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(response.getPayload()).isEqualTo(loadFile("whois_action_idn_punycode.txt"));
  }

  @Test
  void testRun_punycodeDomain_works() {
    Registrar registrar = persistResource(makeRegistrar(
        "evilregistrar", "Yes Virginia", ACTIVE));
    persistResource(
        makeDomain(
            "cat.みんな",
            persistResource(
                FullFieldsTestEntityHelper.makeContact("5372808-ERL", "(◕‿◕)", "lol@cat.みんな")),
            persistResource(
                FullFieldsTestEntityHelper.makeContact(
                    "5372808-IRL", "Santa Claus", "BOFH@cat.みんな")),
            persistResource(
                FullFieldsTestEntityHelper.makeContact("5372808-TRL", "The Raven", "bog@cat.みんな")),
            persistResource(FullFieldsTestEntityHelper.makeHost("ns1.cat.みんな", "1.2.3.4")),
            persistResource(
                FullFieldsTestEntityHelper.makeHost("ns2.cat.みんな", "bad:f00d:cafe::15:beef")),
            registrar));
    persistResources(makeRegistrarPocs(registrar));
    newWhoisAction("domain cat.xn--q9jyb4c\r\n").run();
    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(response.getPayload()).isEqualTo(loadFile("whois_action_idn_punycode.txt"));
  }

  @Test
  void testRun_domainNotFound_returns200OkAndPlainTextResponse() {
    newWhoisAction("domain cat.lol\r\n").run();
    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(response.getPayload()).isEqualTo(loadFile("whois_action_domain_not_found.txt"));
  }

  @Test
  void testRun_domainNotFound_usesCache() {
    // Populate the cache with the nonexistence of this domain.
    assertThat(loadByForeignKeyByCacheIfEnabled(Domain.class, "cat.lol", clock.nowUtc())).isEmpty();
    // Add a new valid cat.lol domain that won't be found because the cache will be hit instead.
    persistActiveDomain("cat.lol");
    newWhoisAction("domain cat.lol\r\n").run();
    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(response.getPayload()).isEqualTo(loadFile("whois_action_domain_not_found.txt"));
  }

  // todo (b/27378695): reenable or delete this test
  @Disabled
  @Test
  void testRun_domainInTestTld_isConsideredNotFound() {
    persistResource(Tld.get("lol").asBuilder().setTldType(Tld.TldType.TEST).build());
    Registrar registrar = persistResource(makeRegistrar(
        "evilregistrar", "Yes Virginia", ACTIVE));
    persistResource(
        makeDomain(
            "cat.lol",
            persistResource(
                FullFieldsTestEntityHelper.makeContact(
                    "5372808-ERL", "Goblin Market", "lol@cat.lol")),
            persistResource(
                FullFieldsTestEntityHelper.makeContact(
                    "5372808-IRL", "Santa Claus", "BOFH@cat.lol")),
            persistResource(
                FullFieldsTestEntityHelper.makeContact("5372808-TRL", "The Raven", "bog@cat.lol")),
            persistResource(FullFieldsTestEntityHelper.makeHost("ns1.cat.lol", "1.2.3.4")),
            persistResource(
                FullFieldsTestEntityHelper.makeHost("ns2.cat.lol", "bad:f00d:cafe::15:beef")),
            registrar));
    persistResources(makeRegistrarPocs(registrar));
    newWhoisAction("domain cat.lol\r\n").run();
    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(response.getPayload()).isEqualTo(loadFile("whois_action_domain_not_found.txt"));
  }

  @Test
  void testRun_domainFlaggedAsDeletedInDatabase_isConsideredNotFound() {
    Registrar registrar;
    persistResource(
        makeDomain(
                "cat.lol",
                persistResource(
                    FullFieldsTestEntityHelper.makeContact(
                        "5372808-ERL", "Peter Murphy", "lol@cat.lol")),
                persistResource(
                    FullFieldsTestEntityHelper.makeContact(
                        "5372808-IRL", "Santa Claus", "BOFH@cat.lol")),
                persistResource(
                    FullFieldsTestEntityHelper.makeContact(
                        "5372808-TRL", "The Raven", "bog@cat.lol")),
                persistResource(FullFieldsTestEntityHelper.makeHost("ns1.cat.lol", "1.2.3.4")),
                persistResource(
                    FullFieldsTestEntityHelper.makeHost("ns2.cat.lol", "bad:f00d:cafe::15:beef")),
                persistResource(registrar = makeRegistrar("example", "Example Registrar", ACTIVE)))
            .asBuilder()
            .setDeletionTime(clock.nowUtc().minusDays(1))
            .build());
    persistResources(makeRegistrarPocs(registrar));
    newWhoisAction("domain cat.lol\r\n").run();
    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(response.getPayload()).isEqualTo(loadFile("whois_action_domain_not_found.txt"));
  }

  /**
   * Create a deleted domain and an active domain with the same label, and make sure only the active
   * one is returned.
   */
  @Test
  void testRun_domainDeletedThenRecreated_isFound() {
    Registrar registrar;
    Domain domain1 =
        persistResource(
            makeDomain(
                    "cat.lol",
                    persistResource(
                        FullFieldsTestEntityHelper.makeContact(
                            "5372808-ERL", "Peter Murphy", "lol@cat.lol")),
                    persistResource(
                        FullFieldsTestEntityHelper.makeContact(
                            "5372808-IRL", "Santa Claus", "BOFH@cat.lol")),
                    persistResource(
                        FullFieldsTestEntityHelper.makeContact(
                            "5372808-TRL", "The Raven", "bog@cat.lol")),
                    persistResource(FullFieldsTestEntityHelper.makeHost("ns1.cat.lol", "1.2.3.4")),
                    persistResource(
                        FullFieldsTestEntityHelper.makeHost(
                            "ns2.cat.lol", "bad:f00d:cafe::15:beef")),
                    persistResource(makeRegistrar("example", "Example Registrar", ACTIVE)))
                .asBuilder()
                .setCreationTimeForTest(clock.nowUtc().minusDays(2))
                .setDeletionTime(clock.nowUtc().minusDays(1))
                .build());
    Domain domain2 =
        persistResource(
            makeDomain(
                    "cat.lol",
                    persistResource(
                        FullFieldsTestEntityHelper.makeContact(
                            "5372809-ERL", "Mrs. Alice Crypto", "alice@example.lol")),
                    persistResource(
                        FullFieldsTestEntityHelper.makeContact(
                            "5372809-IRL", "Mr. Bob Crypto", "bob@example.lol")),
                    persistResource(
                        FullFieldsTestEntityHelper.makeContact(
                            "5372809-TRL", "Dr. Pablo", "pmy@example.lol")),
                    persistResource(
                        FullFieldsTestEntityHelper.makeHost("ns1.google.lol", "9.9.9.9")),
                    persistResource(
                        FullFieldsTestEntityHelper.makeHost("ns2.google.lol", "4311::f143")),
                    persistResource(
                        registrar = makeRegistrar("example", "Example Registrar", ACTIVE)))
                .asBuilder()
                .setCreationTimeForTest(clock.nowUtc())
                .build());
    persistResources(makeRegistrarPocs(registrar));
    assertThat(domain1.getRepoId()).isNotEqualTo(domain2.getRepoId());
    newWhoisAction("domain cat.lol\r\n").run();
    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(response.getPayload()).contains("ns1.google.lol");
  }

  @Test
  void testRun_nameserverQuery_works() {
    persistResource(loadRegistrar("TheRegistrar").asBuilder().setUrl("http://my.fake.url").build());
    persistResource(FullFieldsTestEntityHelper.makeHost("ns1.cat.lol", "1.2.3.4"));
    newWhoisAction("nameserver ns1.cat.lol\r\n").run();
    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(response.getPayload()).isEqualTo(loadFile("whois_action_nameserver.txt"));
  }

  @Test
  void testRun_ipv6_displaysInCollapsedReadableFormat() {
    persistResource(FullFieldsTestEntityHelper.makeHost("ns1.cat.lol", "bad:f00d:cafe::15:beef"));
    newWhoisAction("nameserver ns1.cat.lol\r\n").run();
    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(response.getPayload()).contains("ns1.cat.lol");
    // The most important thing this tests is that the outputted address is compressed!
    assertThat(response.getPayload()).contains("bad:f00d:cafe::15:beef");
    assertThat(response.getPayload()).doesNotContain("bad:f00d:cafe:0:0:0:15:beef");
  }

  @Test
  void testRun_idnNameserver_works() {
    persistResource(FullFieldsTestEntityHelper.makeHost("ns1.cat.みんな", "1.2.3.4"));
    newWhoisAction("nameserver ns1.cat.みんな\r\n").run();
    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(response.getPayload()).contains("ns1.cat.xn--q9jyb4c");
    assertThat(response.getPayload()).contains("1.2.3.4");
  }

  @Test
  void testRun_nameserver_usesCache() {
    persistResource(FullFieldsTestEntityHelper.makeHost("ns1.cat.xn--q9jyb4c", "1.2.3.4"));
    // Populate the cache.
    Host host =
        loadByForeignKeyByCacheIfEnabled(Host.class, "ns1.cat.xn--q9jyb4c", clock.nowUtc()).get();
    // Make a change to the persisted host that won't be seen because the cache will be hit.
    persistResource(
        host.asBuilder()
            .setInetAddresses(ImmutableSet.of(InetAddresses.forString("8.8.8.8")))
            .build());
    newWhoisAction("nameserver ns1.cat.みんな\r\n").run();
    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(response.getPayload()).contains("ns1.cat.xn--q9jyb4c");
    assertThat(response.getPayload()).contains("1.2.3.4");
  }

  @Test
  void testRun_punycodeNameserver_works() {
    persistResource(FullFieldsTestEntityHelper.makeHost("ns1.cat.みんな", "1.2.3.4"));
    newWhoisAction("nameserver ns1.cat.xn--q9jyb4c\r\n").run();
    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(response.getPayload()).contains("ns1.cat.xn--q9jyb4c");
    assertThat(response.getPayload()).contains("1.2.3.4");
  }

  @Test
  void testRun_nameserverNotFound_returns200AndText() {
    persistResource(FullFieldsTestEntityHelper.makeHost("ns1.cat.lol", "1.2.3.4"));
    newWhoisAction("nameserver ns1.cat.lulz\r\n").run();
    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(response.getPayload()).isEqualTo(loadFile("whois_action_nameserver_not_found.txt"));
  }

  @Test
  void testRun_nameserverFlaggedAsDeletedInDatabase_doesntGetLeaked() {
    persistResource(
        FullFieldsTestEntityHelper.makeHost("ns1.cat.lol", "1.2.3.4")
            .asBuilder()
            .setDeletionTime(clock.nowUtc().minusDays(1))
            .build());
    newWhoisAction("nameserver ns1.cat.lol\r\n").run();
    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(response.getPayload()).isEqualTo(loadFile("whois_action_nameserver_not_found.txt"));
  }

  @Test
  void testRun_ipNameserverLookup_works() {
    persistResource(FullFieldsTestEntityHelper.makeHost("ns1.cat.lol", "1.2.3.4"));
    newWhoisAction("nameserver 1.2.3.4").run();
    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(response.getPayload()).contains("ns1.cat.lol");
  }

  @Test
  void testRun_ipMapsToMultipleNameservers_theyAllGetReturned() {
    persistResource(FullFieldsTestEntityHelper.makeHost("ns1.cat.lol", "1.2.3.4"));
    persistResource(FullFieldsTestEntityHelper.makeHost("ns2.cat.lol", "1.2.3.4"));
    newWhoisAction("nameserver 1.2.3.4").run();
    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(response.getPayload()).contains("ns1.cat.lol");
    assertThat(response.getPayload()).contains("ns2.cat.lol");
  }

  @Test
  void testRun_ipMapsToMultipleNameserverInDifferentTlds_showsThemAll() {
    persistResource(FullFieldsTestEntityHelper.makeHost("ns1.cat.lol", "1.2.3.4"));
    persistResource(FullFieldsTestEntityHelper.makeHost("ns1.cat.xn--q9jyb4c", "1.2.3.4"));
    newWhoisAction("nameserver 1.2.3.4").run();
    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(response.getPayload()).contains("ns1.cat.lol");
    assertThat(response.getPayload()).contains("ns1.cat.xn--q9jyb4c");
  }

  @Test
  void testRun_ipNameserverEntityDoesNotExist_returns200NotFound() {
    newWhoisAction("nameserver feed:a:bee::acab\r\n").run();
    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(response.getPayload()).isEqualTo(loadFile("whois_action_ip_not_found.txt"));
  }

  @Test
  void testRun_ipMapsToNameserverUnderNonAuthoritativeTld_notFound() {
    assertThat(getTlds()).doesNotContain("com");
    persistResource(FullFieldsTestEntityHelper.makeHost("ns1.google.com", "1.2.3.4"));
    newWhoisAction("nameserver 1.2.3.4").run();
    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(response.getPayload()).isEqualTo(loadFile("whois_action_ip_not_found.txt"));
  }

  @Test
  void testRun_nameserverUnderNonAuthoritativeTld_notFound() {
    assertThat(getTlds()).doesNotContain("com");
    persistResource(FullFieldsTestEntityHelper.makeHost("ns1.google.com", "1.2.3.4"));
    newWhoisAction("nameserver ns1.google.com").run();
    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(response.getPayload()).isEqualTo(loadFile("whois_action_nameserver_not_found.txt"));
  }

  // todo (b/27378695): reenable or delete this test
  @Disabled
  @Test
  void testRun_nameserverInTestTld_notFound() {
    persistResource(Tld.get("lol").asBuilder().setTldType(Tld.TldType.TEST).build());
    persistResource(FullFieldsTestEntityHelper.makeHost("ns1.cat.lol", "1.2.3.4"));
    newWhoisAction("nameserver ns1.cat.lol").run();
    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(response.getPayload()).isEqualTo(loadFile("whois_action_nameserver_not_found.txt"));
  }

  @Test
  void testRun_registrarLookup_works() {
    Registrar registrar = persistResource(
        makeRegistrar("example", "Example Registrar, Inc.", ACTIVE));
    persistResources(makeRegistrarPocs(registrar));
    // Notice the partial search without "inc".
    newWhoisAction("registrar example registrar").run();
    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(response.getPayload()).isEqualTo(loadFile("whois_action_registrar.txt"));
  }

  @Test
  void testRun_pdtRegistrarLookup_works() {
    Registrar registrar =
        persistResource(
            makeRegistrar("example", "Example Registrar, Inc.", ACTIVE)
                .asBuilder()
                .setIanaIdentifier(9995L)
                .setType(PDT)
                .build());
    persistResources(makeRegistrarPocs(registrar));
    // Notice the partial search without "inc".
    newWhoisAction("registrar example registrar").run();
    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(response.getPayload()).isEqualTo(loadFile("whois_action_registrar.txt"));
  }

  @Test
  void testRun_registrarLookupInPendingState_returnsNotFound() {
    Registrar registrar = persistResource(
        makeRegistrar("example", "Example Registrar, Inc.", Registrar.State.PENDING));
    persistResources(makeRegistrarPocs(registrar));
    newWhoisAction("registrar Example Registrar, Inc.").run();
    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(response.getPayload()).isEqualTo(loadFile("whois_action_registrar_not_found.txt"));
  }

  @Test
  void testRun_registrarLookupWithTestType_returnsNotFound() {
    Registrar registrar = persistResource(
        makeRegistrar("example", "Example Registrar, Inc.", ACTIVE)
            .asBuilder()
            .setIanaIdentifier(null)
            .setType(Registrar.Type.TEST)
            .build());
    persistResources(makeRegistrarPocs(registrar));
    newWhoisAction("registrar Example Registrar, Inc.").run();
    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(response.getPayload()).isEqualTo(loadFile("whois_action_registrar_not_found.txt"));
  }

  @Test
  void testRun_multilevelDomain_isNotConsideredAHostname() {
    Registrar registrar =
        persistResource(makeRegistrar("example", "Example Registrar", ACTIVE));
    persistResource(
        makeDomain(
            "cat.1.test",
            persistResource(
                FullFieldsTestEntityHelper.makeContact("5372808-ERL", "(◕‿◕)", "lol@cat.1.test")),
            persistResource(
                FullFieldsTestEntityHelper.makeContact(
                    "5372808-IRL", "Santa Claus", "BOFH@cat.1.test")),
            persistResource(
                FullFieldsTestEntityHelper.makeContact(
                    "5372808-TRL", "The Raven", "bog@cat.1.test")),
            persistResource(FullFieldsTestEntityHelper.makeHost("ns1.cat.1.test", "1.2.3.4")),
            persistResource(
                FullFieldsTestEntityHelper.makeHost("ns2.cat.1.test", "bad:f00d:cafe::15:beef")),
            registrar));
    persistResources(makeRegistrarPocs(registrar));

    newWhoisAction("domain cat.1.test\r\n").run();
    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(response.getPayload()).contains("Domain Name: cat.1.test\r\n");
  }

  @Test
  void testRun_hostnameWithMultilevelTld_isStillConsideredHostname() {
    persistResource(FullFieldsTestEntityHelper.makeHost("ns1.cat.1.test", "1.2.3.4"));
    newWhoisAction("nameserver ns1.cat.1.test\r\n").run();
    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(response.getPayload()).contains("ns1.cat.1.test");
    assertThat(response.getPayload()).contains("1.2.3.4");
  }

  @Test
  void testRun_metricsLoggedForSuccessfulCommand() {
    persistResource(FullFieldsTestEntityHelper.makeHost("ns1.cat.lol", "1.2.3.4"));
    persistResource(FullFieldsTestEntityHelper.makeHost("ns2.cat.lol", "1.2.3.4"));
    WhoisAction action = newWhoisAction("nameserver 1.2.3.4");
    action.whoisMetrics = mock(WhoisMetrics.class);
    action.run();
    WhoisMetric expected =
        WhoisMetric.builderForRequest(clock)
            .setCommandName("NameserverLookupByIp")
            .setNumResults(2)
            .setStatus(SC_OK)
            .build();
    verify(action.whoisMetrics).recordWhoisMetric(eq(expected));
  }

  @Test
  void testRun_metricsLoggedForUnsuccessfulCommand() {
    WhoisAction action = newWhoisAction("domain cat.lol\r\n");
    action.whoisMetrics = mock(WhoisMetrics.class);
    action.run();
    WhoisMetric expected =
        WhoisMetric.builderForRequest(clock)
            .setCommandName("DomainLookup")
            .setNumResults(0)
            .setStatus(SC_NOT_FOUND)
            .build();
    verify(action.whoisMetrics).recordWhoisMetric(eq(expected));
  }

  @Test
  void testRun_metricsLoggedForInternalServerError() throws Exception {
    persistResource(FullFieldsTestEntityHelper.makeHost("ns1.cat.lol", "1.2.3.4"));
    WhoisAction action = newWhoisAction("ns1.cat.lol");
    action.whoisReader = mock(WhoisReader.class);
    when(action.whoisReader.readCommand(any(Reader.class), eq(false), any(DateTime.class)))
        .thenThrow(new IOException("missing cat interface"));
    action.whoisMetrics = mock(WhoisMetrics.class);

    action.run();
    WhoisMetric expected =
        WhoisMetric.builderForRequest(clock)
            .setNumResults(0)
            .setStatus(SC_INTERNAL_SERVER_ERROR)
            .build();
    verify(action.whoisMetrics).recordWhoisMetric(eq(expected));
    assertThat(response.getPayload()).isEqualTo("Internal Server Error");
  }
}
