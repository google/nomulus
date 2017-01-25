// Copyright 2016 The Nomulus Authors. All Rights Reserved.
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

import static com.google.common.net.MediaType.PLAIN_TEXT_UTF_8;
import static com.google.common.truth.Truth.assertThat;
import static google.registry.testing.DatastoreHelper.createTlds;
import static google.registry.testing.DatastoreHelper.persistResource;
import static google.registry.testing.DatastoreHelper.persistSimpleResources;
import static google.registry.testing.FullFieldsTestEntityHelper.makeContactResource;
import static google.registry.testing.FullFieldsTestEntityHelper.makeDomainResource;
import static google.registry.testing.FullFieldsTestEntityHelper.makeHostResource;
import static google.registry.testing.FullFieldsTestEntityHelper.makeRegistrar;
import static google.registry.testing.FullFieldsTestEntityHelper.makeRegistrarContacts;
import static google.registry.whois.WhoisHelper.loadWhoisTestFile;

import google.registry.model.contact.ContactResource;
import google.registry.model.ofy.Ofy;
import google.registry.model.registrar.Registrar;
import google.registry.model.registry.Registry;
import google.registry.testing.AppEngineRule;
import google.registry.testing.FakeClock;
import google.registry.testing.FakeResponse;
import google.registry.testing.InjectRule;
import org.joda.time.DateTime;
import org.joda.time.Duration;
import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Unit tests for {@link WhoisHttpServer}.
 *
 * <p>This class should be limited to testing the HTTP interface, as the bulk of the WHOIS testing
 * can be found in {@link WhoisServerTest}.
 */
@RunWith(JUnit4.class)
public class WhoisHttpServerTest {

  @Rule
  public final AppEngineRule appEngine = AppEngineRule.builder()
      .withDatastore()
      .build();

  @Rule
  public final InjectRule inject = new InjectRule();

  private final FakeResponse response = new FakeResponse();
  private final FakeClock clock = new FakeClock(DateTime.parse("2009-06-29T20:13:00Z"));

  private WhoisHttpServer newWhoisHttpServer(String pathInfo) {
    WhoisHttpServer result = new WhoisHttpServer();
    result.clock = clock;
    result.expires = Duration.standardHours(1);
    result.requestPath = WhoisHttpServer.PATH + pathInfo;
    result.response = response;
    result.disclaimer = "Doodle Disclaimer";
    result.commandFactory = new WhoisCommandFactory();
    return result;
  }

  @Before
  public void setUp() throws Exception {
    createTlds("lol", "xn--q9jyb4c", "1.test");
    inject.setStaticField(Ofy.class, "clock", clock);
  }

  @After
  public void after() throws Exception {
    // Extra precaution to ensure HTML escaping is working securely.
    assertThat(response.getPayload()).doesNotContain("<script>");
  }

  @Test
  public void testRun_emptyQuery_returns400BadRequestWithPlainTextOutput() throws Exception {
    newWhoisHttpServer("").run();
    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(response.getContentType()).isEqualTo(PLAIN_TEXT_UTF_8);
    assertThat(response.getPayload()).isEqualTo(loadWhoisTestFile("whois_server_no_command.txt"));
  }

  @Test
  public void testRun_badUrlEncoding_returns400BadRequestWithPlainTextOutput() throws Exception {
    newWhoisHttpServer("nic.%u307F%u3093%u306A").run();
    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(response.getContentType()).isEqualTo(PLAIN_TEXT_UTF_8);
    assertThat(response.getPayload())
        .isEqualTo(loadWhoisTestFile("whois_server_malformed_path.txt"));
  }

  @Test
  public void testRun_domainNotFound_returns404StatusAndPlainTextResponse() throws Exception {
    newWhoisHttpServer("/domain/cat.lol").run();
    assertThat(response.getStatus()).isEqualTo(404);
    assertThat(response.getContentType()).isEqualTo(PLAIN_TEXT_UTF_8);
    assertThat(response.getPayload())
        .isEqualTo(loadWhoisTestFile("whois_server_domain_not_found.txt"));
  }

  // todo (b/27378695): reenable or delete this test
  @Ignore
  @Test
  public void testRun_domainInTestTld_isConsideredNotFound() throws Exception {
    persistResource(Registry.get("lol").asBuilder().setTldType(Registry.TldType.TEST).build());
    Registrar registrar = persistResource(makeRegistrar(
        "evilregistrar", "Yes Virginia <script>", Registrar.State.ACTIVE));
    persistResource(makeDomainResource(
        "cat.lol",
        persistResource(makeContactResource("5372808-ERL", "Goblin Market", "lol@cat.lol")),
        persistResource(makeContactResource("5372808-IRL", "Santa Claus", "BOFH@cat.lol")),
        persistResource(makeContactResource("5372808-TRL", "The Raven", "bog@cat.lol")),
        persistResource(makeHostResource("ns1.cat.lol", "1.2.3.4")),
        persistResource(makeHostResource("ns2.cat.lol", "bad:f00d:cafe::15:beef")),
        registrar));
    persistSimpleResources(makeRegistrarContacts(registrar));
    newWhoisHttpServer("/domain/cat.lol").run();
    assertThat(response.getStatus()).isEqualTo(404);
    assertThat(response.getContentType()).isEqualTo(PLAIN_TEXT_UTF_8);
    assertThat(response.getPayload())
        .isEqualTo(loadWhoisTestFile("whois_server_domain_not_found.txt"));
  }

  @Test
  public void testRun_domainQueryIdn_works() throws Exception {
    Registrar registrar = persistResource(makeRegistrar(
        "evilregistrar", "Yes Virginia <script>", Registrar.State.ACTIVE));
    persistResource(makeDomainResource(
        "cat.みんな",
        persistResource(makeContactResource("5372808-ERL", "(◕‿◕)", "lol@cat.みんな")),
        persistResource(makeContactResource("5372808-IRL", "Santa Claus", "BOFH@cat.みんな")),
        persistResource(makeContactResource("5372808-TRL", "The Raven", "bog@cat.みんな")),
        persistResource(makeHostResource("ns1.cat.みんな",  "1.2.3.4")),
        persistResource(makeHostResource("ns2.cat.みんな",  "bad:f00d:cafe::15:beef")),
        registrar));
    persistSimpleResources(makeRegistrarContacts(registrar));
    newWhoisHttpServer("/domain/cat.みんな").run();
    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(response.getPayload()).isEqualTo(loadWhoisTestFile("whois_server_idn_utf8.txt"));
  }

  @Test
  public void testRun_wickedLineFeedForgeryInDatastore_crlfSubstitutedWithSpace() throws Exception {
    String evilName = "Eric\r\nSchmidt";
    ContactResource trl = persistResource(
        makeContactResource("5372808-TRL", evilName, "bog@cat.みんな"));
    persistResource(makeDomainResource(
        "cat.みんな", trl,
        trl,
        trl,
        persistResource(makeHostResource("ns1.cat.みんな", "1.2.3.4")),
        persistResource(makeHostResource("ns2.cat.みんな", "bad:f00d:cafe::15:beef")),
        persistResource( makeRegistrar("example", "Example Registrar", Registrar.State.ACTIVE))));
    newWhoisHttpServer("/domain/cat.みんな").run();
    assertThat(response.getPayload()).contains("Eric  Schmidt");
  }

  @Test
  public void testRun_domainOnly_works() throws Exception {
    persistResource(makeDomainResource(
        "cat.みんな",
        persistResource(makeContactResource("5372808-ERL", "(◕‿◕)", "lol@cat.みんな")),
        persistResource(makeContactResource("5372808-IRL", "Operator", "BOFH@cat.みんな")),
        persistResource(makeContactResource("5372808-TRL", "Eric Schmidt", "bog@cat.みんな")),
        persistResource(makeHostResource("ns1.cat.みんな", "1.2.3.4")),
        persistResource(makeHostResource("ns2.cat.みんな", "bad:f00d:cafe::15:beef")),
        persistResource(makeRegistrar("example", "Example Registrar", Registrar.State.ACTIVE))));
    newWhoisHttpServer("cat.みんな").run();
    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(response.getPayload()).contains("Domain Name: cat.みんな\r\n");
  }

  @Test
  public void testRun_maliciousHtmlInDatastore_getsPurged() throws Exception {
    persistResource(makeDomainResource("cat.みんな",
        persistResource(makeContactResource("5372808-ERL", "(◕‿◕)", "lol@cat.みんな")),
        persistResource(makeContactResource("5372808-IRL", "Operator", "BOFH@cat.みんな")),
        persistResource(
            makeContactResource("5372808-TRL", "<script>alert('lol');</script>", "bog@cat.みんな")),
        persistResource(makeHostResource("ns1.cat.みんな", "1.2.3.4")),
        persistResource(makeHostResource("ns2.cat.みんな", "bad:f00d:cafe::15:beef")),
        persistResource(makeRegistrar("example", "Example Registrar", Registrar.State.ACTIVE))));
    newWhoisHttpServer("cat.みんな").run();
    assertThat(response.getPayload()).doesNotContain("<script>");
  }

  @Test
  public void testRun_hostnameOnly_works() throws Exception {
    persistResource(makeHostResource("ns1.cat.みんな", "1.2.3.4"));
    newWhoisHttpServer("ns1.cat.みんな").run();
    assertThat(response.getPayload()).contains("Server Name: ns1.cat.みんな\r\n");
  }

  @Test
  public void testRun_domainQueryPunycode_works() throws Exception {
    Registrar registrar = persistResource(makeRegistrar(
        "evilregistrar", "Yes Virginia <script>", Registrar.State.ACTIVE));
    persistResource(makeDomainResource(
        "cat.みんな",
        persistResource(makeContactResource("5372808-ERL", "(◕‿◕)", "lol@cat.みんな")),
        persistResource(makeContactResource("5372808-IRL", "Santa Claus", "BOFH@cat.みんな")),
        persistResource(makeContactResource("5372808-TRL", "The Raven", "bog@cat.みんな")),
        persistResource(makeHostResource("ns1.cat.みんな",  "1.2.3.4")),
        persistResource(makeHostResource("ns2.cat.みんな",  "bad:f00d:cafe::15:beef")),
        registrar));
    persistSimpleResources(makeRegistrarContacts(registrar));
    newWhoisHttpServer("/domain/cat.xn--q9jyb4c").run();
    assertThat(response.getPayload()).isEqualTo(loadWhoisTestFile("whois_server_idn_utf8.txt"));
  }

  @Test
  public void testRun_nameserverQuery_works() throws Exception {
    persistResource(makeHostResource("ns1.cat.lol", "1.2.3.4"));
    newWhoisHttpServer("/nameserver/ns1.cat.lol").run();
    assertThat(response.getPayload()).isEqualTo(loadWhoisTestFile("whois_server_nameserver.txt"));
  }

  // todo (b/27378695): reenable or delete this test
  @Ignore
  @Test
  public void testRun_nameserverQueryInTestTld_notFound() throws Exception {
    persistResource(makeHostResource("ns1.cat.lol", "1.2.3.4"));
    newWhoisHttpServer("/nameserver/ns1.cat.lol").run();
    assertThat(response.getPayload()).isEqualTo(loadWhoisTestFile("whois_server_nameserver.txt"));
  }

  @Test
  public void testRun_lastUpdateTimestamp_isPresentInResponse() throws Exception {
    clock.setTo(DateTime.parse("2020-07-12T23:52:43Z"));
    persistResource(makeHostResource("ns1.cat.lol", "1.2.3.4"));
    newWhoisHttpServer("/nameserver/ns1.cat.lol").run();
    assertThat(response.getPayload())
        .contains(">>> Last update of WHOIS database: 2020-07-12T23:52:43Z <<<");
  }

  @Test
  public void testRun_nameserverQueryIdn_works() throws Exception {
    persistResource(makeHostResource("ns1.cat.みんな", "1.2.3.4"));
    newWhoisHttpServer("/nameserver/ns1.cat.みんな").run();
    assertThat(response.getPayload()).contains("ns1.cat.みんな");
    assertThat(response.getPayload()).contains("1.2.3.4");
  }

  @Test
  public void testRun_nameserverQueryPunycode_works() throws Exception {
    persistResource(makeHostResource("ns1.cat.みんな", "1.2.3.4"));
    newWhoisHttpServer("/nameserver/ns1.cat.xn--q9jyb4c").run();
    assertThat(response.getPayload()).contains("ns1.cat.みんな");
    assertThat(response.getPayload()).contains("1.2.3.4");
  }

  @Test
  public void testRun_trailingSlashInPath_getsIgnored() throws Exception {
    persistResource(makeHostResource("ns1.cat.みんな", "1.2.3.4"));
    newWhoisHttpServer("/nameserver/ns1.cat.xn--q9jyb4c/").run();
    assertThat(response.getPayload()).contains("ns1.cat.みんな");
    assertThat(response.getPayload()).contains("1.2.3.4");
  }

  @Test
  public void testRun_uppercaseDomain_ignoresCasing() throws Exception {
    persistResource(makeDomainResource(
        "cat.lol",
        persistResource(makeContactResource("5372808-ERL", "Peter Murphy", "lol@cat.lol")),
        persistResource(makeContactResource("5372808-IRL", "Operator", "BOFH@cat.lol")),
        persistResource(makeContactResource("5372808-TRL", "Eric Schmidt", "bog@cat.lol")),
        persistResource(makeHostResource("ns1.cat.lol", "1.2.3.4")),
        persistResource(makeHostResource("ns2.cat.lol", "bad:f00d:cafe::15:beef")),
        persistResource(makeRegistrar("example", "Example Registrar", Registrar.State.ACTIVE))));
    newWhoisHttpServer("/domain/cat.LOL").run();
    assertThat(response.getPayload()).contains("Domain Name: cat.lol\r\n");
  }

  @Test
  public void testRun_hairyPath_getsDecoded() throws Exception {
    persistResource(makeDomainResource(
        "cat.lol",
        persistResource(makeContactResource("5372808-ERL", "Peter Murphy", "lol@cat.lol")),
        persistResource(makeContactResource("5372808-IRL", "Operator", "BOFH@cat.lol")),
        persistResource(makeContactResource("5372808-TRL", "Eric Schmidt", "bog@cat.lol")),
        persistResource(makeHostResource("ns1.cat.lol", "1.2.3.4")),
        persistResource(makeHostResource("ns2.cat.lol", "bad:f00d:cafe::15:beef")),
        persistResource(makeRegistrar("example", "Example Registrar", Registrar.State.ACTIVE))));
    // python -c "print ''.join('%' + hex(ord(c))[2:] for c in 'cat.lol')"
    newWhoisHttpServer("/domain/%63%61%74%2e%6c%6f%6c").run();
    assertThat(response.getPayload()).contains("Domain Name: cat.lol\r\n");
  }

  @Test
  public void testRun_registrarLookup_works() throws Exception {
    Registrar registrar = persistResource(
        makeRegistrar("example", "Example Registrar, Inc.", Registrar.State.ACTIVE));
    persistSimpleResources(makeRegistrarContacts(registrar));
    // Notice the partial search without "inc".
    newWhoisHttpServer("/registrar/Example%20Registrar").run();
    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(response.getPayload()).isEqualTo(loadWhoisTestFile("whois_server_registrar.txt"));
  }

  @Test
  public void testRun_registrarLookupInPendingState_returnsNotFound() throws Exception {
    Registrar registrar = persistResource(
        makeRegistrar("example", "Example Registrar, Inc.", Registrar.State.PENDING));
    persistSimpleResources(makeRegistrarContacts(registrar));
    newWhoisHttpServer("/registrar/Example%20Registrar,%20Inc.").run();
    assertThat(response.getStatus()).isEqualTo(404);
    assertThat(response.getPayload())
        .isEqualTo(loadWhoisTestFile("whois_server_registrar_not_found.txt"));
  }

  @Test
  public void testRun_registrarLookupWithTestType_returnsNotFound() throws Exception {
    Registrar registrar = persistResource(
        makeRegistrar("example", "Example Registrar, Inc.", Registrar.State.ACTIVE)
            .asBuilder().setType(Registrar.Type.TEST).setIanaIdentifier(null).build());
    persistSimpleResources(makeRegistrarContacts(registrar));
    newWhoisHttpServer("/registrar/Example%20Registrar,%20Inc.").run();
    assertThat(response.getStatus()).isEqualTo(404);
    assertThat(response.getPayload())
        .isEqualTo(loadWhoisTestFile("whois_server_registrar_not_found.txt"));
  }
}
