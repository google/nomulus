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

import static com.google.common.net.MediaType.PLAIN_TEXT_UTF_8;
import static com.google.common.truth.Truth.assertThat;
import static google.registry.testing.DatastoreHelper.createTlds;
import static google.registry.testing.DatastoreHelper.loadRegistrar;
import static google.registry.testing.DatastoreHelper.persistResource;
import static google.registry.testing.DatastoreHelper.persistSimpleResources;
import static google.registry.testing.FullFieldsTestEntityHelper.makeContactResource;
import static google.registry.testing.FullFieldsTestEntityHelper.makeDomainBase;
import static google.registry.testing.FullFieldsTestEntityHelper.makeHostResource;
import static google.registry.testing.FullFieldsTestEntityHelper.makeRegistrar;
import static google.registry.testing.FullFieldsTestEntityHelper.makeRegistrarContacts;
import static google.registry.whois.WhoisTestData.loadFile;
import static javax.servlet.http.HttpServletResponse.SC_BAD_REQUEST;
import static javax.servlet.http.HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
import static javax.servlet.http.HttpServletResponse.SC_OK;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import google.registry.model.contact.ContactResource;
import google.registry.model.ofy.Ofy;
import google.registry.model.registrar.Registrar;
import google.registry.model.registry.Registry;
import google.registry.testing.AppEngineExtension;
import google.registry.testing.FakeClock;
import google.registry.testing.FakeResponse;
import google.registry.testing.InjectExtension;
import google.registry.whois.WhoisMetrics.WhoisMetric;
import java.io.IOException;
import java.io.Reader;
import org.joda.time.DateTime;
import org.joda.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Unit tests for {@link WhoisHttpAction}.
 *
 * <p>This class should be limited to testing the HTTP interface, as the bulk of the WHOIS testing
 * can be found in {@link WhoisActionTest}.
 */
class WhoisHttpActionTest {

  @RegisterExtension
  final AppEngineExtension appEngine =
      AppEngineExtension.builder().withDatastoreAndCloudSql().build();

  @RegisterExtension final InjectExtension inject = new InjectExtension();

  private final FakeResponse response = new FakeResponse();
  private final FakeClock clock = new FakeClock(DateTime.parse("2009-06-29T20:13:00Z"));

  private WhoisHttpAction newWhoisHttpAction(String pathInfo) {
    WhoisHttpAction whoisAction = new WhoisHttpAction();
    whoisAction.clock = clock;
    whoisAction.expires = Duration.standardHours(1);
    whoisAction.requestPath = WhoisHttpAction.PATH + pathInfo;
    whoisAction.response = response;
    whoisAction.whoisReader =
        new WhoisReader(new WhoisCommandFactory(), "Please contact registrar");
    whoisAction.whoisMetrics = new WhoisMetrics();
    whoisAction.metricBuilder = WhoisMetric.builderForRequest(clock);
    whoisAction.disclaimer =
        "Doodle Disclaimer\nI exist so that carriage return\nin disclaimer can be tested.";
    return whoisAction;
  }

  @BeforeEach
  void beforeEach() {
    createTlds("lol", "xn--q9jyb4c", "1.test");
    inject.setStaticField(Ofy.class, "clock", clock);
  }

  @Test
  void testRun_emptyQuery_returns400BadRequestWithPlainTextOutput() {
    newWhoisHttpAction("").run();
    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(response.getContentType()).isEqualTo(PLAIN_TEXT_UTF_8);
    assertThat(response.getPayload()).isEqualTo(loadFile("whois_action_no_command.txt"));
  }

  @Test
  void testRun_badUrlEncoding_returns400BadRequestWithPlainTextOutput() {
    newWhoisHttpAction("nic.%u307F%u3093%u306A").run();
    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(response.getContentType()).isEqualTo(PLAIN_TEXT_UTF_8);
    assertThat(response.getPayload()).isEqualTo(loadFile("whois_action_malformed_path.txt"));
  }

  @Test
  void testRun_domainNotFound_returns404StatusAndPlainTextResponse() {
    newWhoisHttpAction("/domain/cat.lol").run();
    assertThat(response.getStatus()).isEqualTo(404);
    assertThat(response.getContentType()).isEqualTo(PLAIN_TEXT_UTF_8);
    assertThat(response.getPayload()).isEqualTo(loadFile("whois_action_domain_not_found.txt"));
  }

  // todo (b/27378695): reenable or delete this test
  @Disabled
  @Test
  void testRun_domainInTestTld_isConsideredNotFound() {
    persistResource(Registry.get("lol").asBuilder().setTldType(Registry.TldType.TEST).build());
    Registrar registrar = persistResource(makeRegistrar(
        "evilregistrar", "Yes Virginia", Registrar.State.ACTIVE));
    persistResource(makeDomainBase(
        "cat.lol",
        persistResource(makeContactResource("5372808-ERL", "Goblin Market", "lol@cat.lol")),
        persistResource(makeContactResource("5372808-IRL", "Santa Claus", "BOFH@cat.lol")),
        persistResource(makeContactResource("5372808-TRL", "The Raven", "bog@cat.lol")),
        persistResource(makeHostResource("ns1.cat.lol", "1.2.3.4")),
        persistResource(makeHostResource("ns2.cat.lol", "bad:f00d:cafe::15:beef")),
        registrar));
    persistSimpleResources(makeRegistrarContacts(registrar));
    newWhoisHttpAction("/domain/cat.lol").run();
    assertThat(response.getStatus()).isEqualTo(404);
    assertThat(response.getContentType()).isEqualTo(PLAIN_TEXT_UTF_8);
    assertThat(response.getPayload()).isEqualTo(loadFile("whois_action_domain_not_found.txt"));
  }

  @Test
  void testRun_domainQueryIdn_works() {
    Registrar registrar = persistResource(makeRegistrar(
        "evilregistrar", "Yes Virginia", Registrar.State.ACTIVE));
    persistResource(makeDomainBase(
        "cat.みんな",
        persistResource(makeContactResource("5372808-ERL", "(◕‿◕)", "lol@cat.みんな")),
        persistResource(makeContactResource("5372808-IRL", "Santa Claus", "BOFH@cat.みんな")),
        persistResource(makeContactResource("5372808-TRL", "The Raven", "bog@cat.みんな")),
        persistResource(makeHostResource("ns1.cat.みんな",  "1.2.3.4")),
        persistResource(makeHostResource("ns2.cat.みんな",  "bad:f00d:cafe::15:beef")),
        registrar));
    persistSimpleResources(makeRegistrarContacts(registrar));
    newWhoisHttpAction("/domain/cat.みんな").run();
    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(response.getPayload()).isEqualTo(loadFile("whois_action_idn_utf8.txt"));
  }

  @Test
  void testRun_wickedLineFeedForgeryInDatastore_crlfSubstitutedWithSpace() {
    ContactResource trl = makeContactResource("5372808-TRL", "Eric Schmidt", "bog@cat.みんな");
    trl =
        persistResource(
            trl.asBuilder()
                .setInternationalizedPostalInfo(
                    trl.getInternationalizedPostalInfo()
                        .asBuilder()
                        .setOrg("Galactic\r\nEmpire")
                        .build())
                .build());
    persistResource(makeDomainBase(
        "cat.みんな", trl,
        trl,
        trl,
        persistResource(makeHostResource("ns1.cat.みんな", "1.2.3.4")),
        persistResource(makeHostResource("ns2.cat.みんな", "bad:f00d:cafe::15:beef")),
        persistResource(makeRegistrar("example", "Example Registrar", Registrar.State.ACTIVE))));
    newWhoisHttpAction("/domain/cat.みんな").run();
    assertThat(response.getPayload()).contains("Galactic  Empire");
  }

  @Test
  void testRun_domainOnly_works() {
    persistResource(makeDomainBase(
        "cat.みんな",
        persistResource(makeContactResource("5372808-ERL", "(◕‿◕)", "lol@cat.みんな")),
        persistResource(makeContactResource("5372808-IRL", "Operator", "BOFH@cat.みんな")),
        persistResource(makeContactResource("5372808-TRL", "Eric Schmidt", "bog@cat.みんな")),
        persistResource(makeHostResource("ns1.cat.みんな", "1.2.3.4")),
        persistResource(makeHostResource("ns2.cat.みんな", "bad:f00d:cafe::15:beef")),
        persistResource(makeRegistrar("example", "Example Registrar", Registrar.State.ACTIVE))));
    newWhoisHttpAction("cat.みんな").run();
    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(response.getPayload()).contains("Domain Name: cat.みんな\r\n");
  }

  @Test
  void testRun_hostnameOnly_works() {
    persistResource(makeHostResource("ns1.cat.みんな", "1.2.3.4"));
    newWhoisHttpAction("ns1.cat.みんな").run();
    assertThat(response.getPayload()).contains("Server Name: ns1.cat.みんな\r\n");
  }

  @Test
  void testRun_domainQueryPunycode_works() {
    Registrar registrar = persistResource(makeRegistrar(
        "evilregistrar", "Yes Virginia", Registrar.State.ACTIVE));
    persistResource(makeDomainBase(
        "cat.みんな",
        persistResource(makeContactResource("5372808-ERL", "(◕‿◕)", "lol@cat.みんな")),
        persistResource(makeContactResource("5372808-IRL", "Santa Claus", "BOFH@cat.みんな")),
        persistResource(makeContactResource("5372808-TRL", "The Raven", "bog@cat.みんな")),
        persistResource(makeHostResource("ns1.cat.みんな",  "1.2.3.4")),
        persistResource(makeHostResource("ns2.cat.みんな",  "bad:f00d:cafe::15:beef")),
        registrar));
    persistSimpleResources(makeRegistrarContacts(registrar));
    newWhoisHttpAction("/domain/cat.xn--q9jyb4c").run();
    assertThat(response.getPayload()).isEqualTo(loadFile("whois_action_idn_utf8.txt"));
  }

  @Test
  void testRun_nameserverQuery_works() {
    persistResource(loadRegistrar("TheRegistrar").asBuilder().setUrl("http://my.fake.url").build());
    persistResource(makeHostResource("ns1.cat.lol", "1.2.3.4"));
    newWhoisHttpAction("/nameserver/ns1.cat.lol").run();
    assertThat(response.getPayload()).isEqualTo(loadFile("whois_action_nameserver.txt"));
  }

  // todo (b/27378695): reenable or delete this test
  @Disabled
  @Test
  void testRun_nameserverQueryInTestTld_notFound() {
    persistResource(makeHostResource("ns1.cat.lol", "1.2.3.4"));
    newWhoisHttpAction("/nameserver/ns1.cat.lol").run();
    assertThat(response.getPayload()).isEqualTo(loadFile("whois_action_nameserver.txt"));
  }

  @Test
  void testRun_lastUpdateTimestamp_isPresentInResponse() {
    clock.setTo(DateTime.parse("2020-07-12T23:52:43Z"));
    persistResource(makeHostResource("ns1.cat.lol", "1.2.3.4"));
    newWhoisHttpAction("/nameserver/ns1.cat.lol").run();
    assertThat(response.getPayload())
        .contains(">>> Last update of WHOIS database: 2020-07-12T23:52:43Z <<<");
  }

  @Test
  void testRun_nameserverQueryIdn_works() {
    persistResource(makeHostResource("ns1.cat.みんな", "1.2.3.4"));
    newWhoisHttpAction("/nameserver/ns1.cat.みんな").run();
    assertThat(response.getPayload()).contains("ns1.cat.みんな");
    assertThat(response.getPayload()).contains("1.2.3.4");
  }

  @Test
  void testRun_nameserverQueryPunycode_works() {
    persistResource(makeHostResource("ns1.cat.みんな", "1.2.3.4"));
    newWhoisHttpAction("/nameserver/ns1.cat.xn--q9jyb4c").run();
    assertThat(response.getPayload()).contains("ns1.cat.みんな");
    assertThat(response.getPayload()).contains("1.2.3.4");
  }

  @Test
  void testRun_trailingSlashInPath_getsIgnored() {
    persistResource(makeHostResource("ns1.cat.みんな", "1.2.3.4"));
    newWhoisHttpAction("/nameserver/ns1.cat.xn--q9jyb4c/").run();
    assertThat(response.getPayload()).contains("ns1.cat.みんな");
    assertThat(response.getPayload()).contains("1.2.3.4");
  }

  @Test
  void testRun_uppercaseDomain_ignoresCasing() {
    persistResource(makeDomainBase(
        "cat.lol",
        persistResource(makeContactResource("5372808-ERL", "Peter Murphy", "lol@cat.lol")),
        persistResource(makeContactResource("5372808-IRL", "Operator", "BOFH@cat.lol")),
        persistResource(makeContactResource("5372808-TRL", "Eric Schmidt", "bog@cat.lol")),
        persistResource(makeHostResource("ns1.cat.lol", "1.2.3.4")),
        persistResource(makeHostResource("ns2.cat.lol", "bad:f00d:cafe::15:beef")),
        persistResource(makeRegistrar("example", "Example Registrar", Registrar.State.ACTIVE))));
    newWhoisHttpAction("/domain/cat.LOL").run();
    assertThat(response.getPayload()).contains("Domain Name: cat.lol\r\n");
  }

  @Test
  void testRun_hairyPath_getsDecoded() {
    persistResource(makeDomainBase(
        "cat.lol",
        persistResource(makeContactResource("5372808-ERL", "Peter Murphy", "lol@cat.lol")),
        persistResource(makeContactResource("5372808-IRL", "Operator", "BOFH@cat.lol")),
        persistResource(makeContactResource("5372808-TRL", "Eric Schmidt", "bog@cat.lol")),
        persistResource(makeHostResource("ns1.cat.lol", "1.2.3.4")),
        persistResource(makeHostResource("ns2.cat.lol", "bad:f00d:cafe::15:beef")),
        persistResource(makeRegistrar("example", "Example Registrar", Registrar.State.ACTIVE))));
    // python -c "print ''.join('%' + hex(ord(c))[2:] for c in 'cat.lol')"
    newWhoisHttpAction("/domain/%63%61%74%2e%6c%6f%6c").run();
    assertThat(response.getPayload()).contains("Domain Name: cat.lol\r\n");
  }

  @Test
  void testRun_registrarLookup_works() {
    Registrar registrar = persistResource(
        makeRegistrar("example", "Example Registrar, Inc.", Registrar.State.ACTIVE));
    persistSimpleResources(makeRegistrarContacts(registrar));
    // Notice the partial search without "inc".
    newWhoisHttpAction("/registrar/Example%20Registrar").run();
    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(response.getPayload()).isEqualTo(loadFile("whois_action_registrar.txt"));
  }

  @Test
  void testRun_registrarLookupInPendingState_returnsNotFound() {
    Registrar registrar = persistResource(
        makeRegistrar("example", "Example Registrar, Inc.", Registrar.State.PENDING));
    persistSimpleResources(makeRegistrarContacts(registrar));
    newWhoisHttpAction("/registrar/Example%20Registrar,%20Inc.").run();
    assertThat(response.getStatus()).isEqualTo(404);
    assertThat(response.getPayload()).isEqualTo(loadFile("whois_action_registrar_not_found.txt"));
  }

  @Test
  void testRun_registrarLookupWithTestType_returnsNotFound() {
    Registrar registrar = persistResource(
        makeRegistrar("example", "Example Registrar, Inc.", Registrar.State.ACTIVE)
            .asBuilder().setType(Registrar.Type.TEST).setIanaIdentifier(null).build());
    persistSimpleResources(makeRegistrarContacts(registrar));
    newWhoisHttpAction("/registrar/Example%20Registrar,%20Inc.").run();
    assertThat(response.getStatus()).isEqualTo(404);
    assertThat(response.getPayload()).isEqualTo(loadFile("whois_action_registrar_not_found.txt"));
  }

  @Test
  void testRun_metricsLoggedForSuccessfulCommand() {
    persistResource(makeHostResource("ns1.cat.lol", "1.2.3.4"));
    WhoisHttpAction action = newWhoisHttpAction("/nameserver/ns1.cat.lol");
    action.whoisMetrics = mock(WhoisMetrics.class);
    action.run();
    WhoisMetric expected =
        WhoisMetric.builderForRequest(clock)
            .setCommandName("NameserverLookupByHost")
            .setNumResults(1)
            .setStatus(SC_OK)
            .build();
    verify(action.whoisMetrics).recordWhoisMetric(eq(expected));
  }

  @Test
  void testRun_metricsLoggedForUnsuccessfulCommand() {
    WhoisHttpAction action = newWhoisHttpAction("nic.%u307F%u3093%u306A");
    action.whoisMetrics = mock(WhoisMetrics.class);
    action.run();
    WhoisMetric expected =
        WhoisMetric.builderForRequest(clock).setNumResults(0).setStatus(SC_BAD_REQUEST).build();
    verify(action.whoisMetrics).recordWhoisMetric(eq(expected));
  }

  @Test
  void testRun_metricsLoggedForInternalServerError() throws Exception {
    persistResource(makeHostResource("ns1.cat.lol", "1.2.3.4"));
    WhoisHttpAction action = newWhoisHttpAction("ns1.cat.lol");
    action.whoisReader = mock(WhoisReader.class);
    when(action.whoisReader.readCommand(any(Reader.class), eq(false), any(DateTime.class)))
        .thenThrow(new IOException("missing cat interface"));
    action.whoisMetrics = mock(WhoisMetrics.class);

    RuntimeException thrown = assertThrows(RuntimeException.class, action::run);
    assertThat(thrown).hasCauseThat().hasMessageThat().isEqualTo("missing cat interface");
    WhoisMetric expected =
        WhoisMetric.builderForRequest(clock)
            .setNumResults(0)
            .setStatus(SC_INTERNAL_SERVER_ERROR)
            .build();
    verify(action.whoisMetrics).recordWhoisMetric(eq(expected));
  }
}
