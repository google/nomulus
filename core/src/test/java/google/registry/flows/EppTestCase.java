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

package google.registry.flows;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth8.assertThat;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.testing.DatabaseHelper.getOnlyHistoryEntryOfType;
import static google.registry.testing.DatabaseHelper.loadAllOf;
import static google.registry.testing.DatabaseHelper.stripBillingEventId;
import static google.registry.testing.TestDataHelper.loadFile;
import static google.registry.xml.XmlTestUtils.assertXmlEqualsWithMessage;
import static java.nio.charset.StandardCharsets.UTF_8;
import static javax.servlet.http.HttpServletResponse.SC_OK;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.net.MediaType;
import com.googlecode.objectify.Key;
import google.registry.flows.EppTestComponent.FakesAndMocksModule;
import google.registry.model.billing.BillingEvent;
import google.registry.model.billing.BillingEvent.Flag;
import google.registry.model.billing.BillingEvent.OneTime;
import google.registry.model.billing.BillingEvent.Reason;
import google.registry.model.domain.DomainBase;
import google.registry.model.domain.DomainHistory;
import google.registry.model.eppcommon.EppXmlTransformer;
import google.registry.model.ofy.Ofy;
import google.registry.model.registry.Registry;
import google.registry.model.reporting.HistoryEntry.Type;
import google.registry.monitoring.whitebox.EppMetric;
import google.registry.persistence.VKey;
import google.registry.testing.FakeClock;
import google.registry.testing.FakeHttpSession;
import google.registry.testing.FakeResponse;
import google.registry.testing.InjectExtension;
import google.registry.util.ProxyHttpHeaders;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import javax.annotation.Nullable;
import org.joda.money.Money;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.RegisterExtension;

public class EppTestCase {

  private static final MediaType APPLICATION_EPP_XML_UTF8 =
      MediaType.create("application", "epp+xml").withCharset(UTF_8);

  @RegisterExtension public final InjectExtension inject = new InjectExtension();

  protected final FakeClock clock = new FakeClock();

  private SessionMetadata sessionMetadata;
  private TransportCredentials credentials = new PasswordOnlyTransportCredentials();
  private EppMetric.Builder eppMetricBuilder;
  private boolean isSuperuser;

  @BeforeEach
  public void beforeEachEppTestCase() {
    // For transactional flows
    inject.setStaticField(Ofy.class, "clock", clock);
  }

  /**
   * Set the transport credentials.
   *
   * <p>When the credentials are null, the login flow still checks the EPP password from the xml,
   * which is sufficient for all tests that aren't explicitly testing a form of login credentials
   * such as {@link EppLoginTlsTest}. Therefore, only those tests should call this method.
   */
  void setTransportCredentials(TransportCredentials credentials) {
    this.credentials = credentials;
  }

  protected void setIsSuperuser(boolean isSuperuser) {
    this.isSuperuser = isSuperuser;
  }

  public class CommandAsserter {
    private final String inputFilename;
    private @Nullable final Map<String, String> inputSubstitutions;
    private DateTime now;

    private CommandAsserter(
        String inputFilename, @Nullable Map<String, String> inputSubstitutions) {
      this.inputFilename = inputFilename;
      this.inputSubstitutions = inputSubstitutions;
      this.now = clock.nowUtc();
    }

    public CommandAsserter atTime(DateTime now) {
      this.now = now;
      return this;
    }

    public CommandAsserter atTime(String now) {
      return atTime(DateTime.parse(now));
    }

    public String hasResponse(String outputFilename) throws Exception {
      return hasResponse(outputFilename, null);
    }

    public String hasResponse(
        String outputFilename, @Nullable Map<String, String> outputSubstitutions) throws Exception {
      return assertCommandAndResponse(
          inputFilename, inputSubstitutions, outputFilename, outputSubstitutions, now);
    }

    public String hasSuccessfulLogin() throws Exception {
      return assertLoginCommandAndResponse(inputFilename, inputSubstitutions, null, clock.nowUtc());
    }
  }

  protected CommandAsserter assertThatCommand(String inputFilename) {
    return assertThatCommand(inputFilename, null);
  }

  protected CommandAsserter assertThatCommand(
      String inputFilename, @Nullable Map<String, String> inputSubstitutions) {
    return new CommandAsserter(inputFilename, inputSubstitutions);
  }

  CommandAsserter assertThatLogin(String clientId, String password) {
    return assertThatCommand("login.xml", ImmutableMap.of("CLID", clientId, "PW", password))
        .atTime(clock.nowUtc());
  }

  protected void assertThatLoginSucceeds(String clientId, String password) throws Exception {
    assertThatLogin(clientId, password).atTime(clock.nowUtc()).hasSuccessfulLogin();
  }

  protected void assertThatLogoutSucceeds() throws Exception {
    assertThatCommand("logout.xml").hasResponse("logout_response.xml");
  }

  private String assertLoginCommandAndResponse(
      String inputFilename,
      @Nullable Map<String, String> inputSubstitutions,
      @Nullable Map<String, String> outputSubstitutions,
      DateTime now)
      throws Exception {
    String outputFilename = "generic_success_response.xml";
    clock.setTo(now);
    String input = loadFile(EppTestCase.class, inputFilename, inputSubstitutions);
    String expectedOutput = loadFile(EppTestCase.class, outputFilename, outputSubstitutions);
    setUpSession();
    FakeResponse response = executeXmlCommand(input);

    // Check that the logged-in header was added to the response
    assertThat(response.getHeaders())
        .isEqualTo(ImmutableMap.of(ProxyHttpHeaders.LOGGED_IN, "true"));

    return verifyAndReturnOutput(
        response.getPayload(), expectedOutput, inputFilename, outputFilename);
  }

  private String assertCommandAndResponse(
      String inputFilename,
      @Nullable Map<String, String> inputSubstitutions,
      String outputFilename,
      @Nullable Map<String, String> outputSubstitutions,
      DateTime now)
      throws Exception {
    clock.setTo(now);
    String input = loadFile(EppTestCase.class, inputFilename, inputSubstitutions);
    String expectedOutput = loadFile(EppTestCase.class, outputFilename, outputSubstitutions);
    setUpSession();
    FakeResponse response = executeXmlCommand(input);

    // Checks that the Logged-In header is not in the response. If testing the login command, use
    // assertLoginCommandAndResponse instead of this method.
    assertThat(response.getHeaders()).doesNotContainEntry(ProxyHttpHeaders.LOGGED_IN, "true");

    return verifyAndReturnOutput(
        response.getPayload(), expectedOutput, inputFilename, outputFilename);
  }

  private void setUpSession() {
    if (sessionMetadata == null) {
      sessionMetadata =
          new HttpSessionMetadata(new FakeHttpSession()) {
            @Override
            public void invalidate() {
              // When a session is invalidated, reset the sessionMetadata field.
              super.invalidate();
              EppTestCase.this.sessionMetadata = null;
            }
          };
    }
  }

  private String verifyAndReturnOutput(
      String actualOutput, String expectedOutput, String inputFilename, String outputFilename)
      throws Exception {
    // Run the resulting xml through the unmarshaller to verify that it was valid.
    EppXmlTransformer.validateOutput(actualOutput);
    assertXmlEqualsWithMessage(
        expectedOutput,
        actualOutput,
        "Running " + inputFilename + " => " + outputFilename,
        "epp.response.resData.infData.roid",
        "epp.response.trID.svTRID");
    tm().clearSessionCache(); // Clear the cache like OfyFilter would.
    return actualOutput;
  }

  private FakeResponse executeXmlCommand(String inputXml) {
    EppRequestHandler handler = new EppRequestHandler();
    FakeResponse response = new FakeResponse();
    handler.response = response;
    eppMetricBuilder = EppMetric.builderForRequest(clock);
    handler.eppController = DaggerEppTestComponent.builder()
        .fakesAndMocksModule(FakesAndMocksModule.create(clock, eppMetricBuilder))
        .build()
        .startRequest()
        .eppController();
    handler.executeEpp(
        sessionMetadata,
        credentials,
        EppRequestSource.UNIT_TEST,
        false,  // Not dryRun.
        isSuperuser,
        inputXml.getBytes(UTF_8));
    assertThat(response.getStatus()).isEqualTo(SC_OK);
    assertThat(response.getContentType()).isEqualTo(APPLICATION_EPP_XML_UTF8);
    return response;
  }

  EppMetric getRecordedEppMetric() {
    return eppMetricBuilder.build();
  }

  /** Create the two administrative contacts and two hosts. */
  void createContactsAndHosts() throws Exception {
    DateTime createTime = DateTime.parse("2000-06-01T00:00:00Z");
    createContacts(createTime);
    assertThatCommand("host_create.xml", ImmutableMap.of("HOSTNAME", "ns1.example.external"))
        .atTime(createTime.plusMinutes(2))
        .hasResponse(
            "host_create_response.xml",
            ImmutableMap.of(
                "HOSTNAME", "ns1.example.external",
                "CRDATE", createTime.plusMinutes(2).toString()));
    assertThatCommand("host_create.xml", ImmutableMap.of("HOSTNAME", "ns2.example.external"))
        .atTime(createTime.plusMinutes(3))
        .hasResponse(
            "host_create_response.xml",
            ImmutableMap.of(
                "HOSTNAME", "ns2.example.external",
                "CRDATE", createTime.plusMinutes(3).toString()));
  }

  protected void createContacts(DateTime createTime) throws Exception {
    assertThatCommand("contact_create_sh8013.xml")
        .atTime(createTime)
        .hasResponse(
            "contact_create_response_sh8013.xml", ImmutableMap.of("CRDATE", createTime.toString()));
    assertThatCommand("contact_create_jd1234.xml")
        .atTime(createTime.plusMinutes(1))
        .hasResponse(
            "contact_create_response_jd1234.xml",
            ImmutableMap.of("CRDATE", createTime.plusMinutes(1).toString()));
  }

  /** Creates the domain fakesite.example with two nameservers on it. */
  void createFakesite() throws Exception {
    createContactsAndHosts();
    assertThatCommand("domain_create_fakesite.xml")
        .atTime("2000-06-01T00:04:00Z")
        .hasResponse(
            "domain_create_response.xml",
            ImmutableMap.of(
                "DOMAIN", "fakesite.example",
                "CRDATE", "2000-06-01T00:04:00.0Z",
                "EXDATE", "2002-06-01T00:04:00.0Z"));
    assertThatCommand("domain_info_fakesite.xml")
        .atTime("2000-06-06T00:00:00Z")
        .hasResponse("domain_info_response_fakesite_ok.xml");
  }

  /** Creates ns3.fakesite.example as a host, then adds it to fakesite. */
  void createSubordinateHost() throws Exception {
    // Add the fakesite nameserver (requires that domain is already created).
    assertThatCommand("host_create_fakesite.xml")
        .atTime("2000-06-06T00:01:00Z")
        .hasResponse("host_create_response_fakesite.xml");
    // Add new nameserver to domain.
    assertThatCommand("domain_update_add_nameserver_fakesite.xml")
        .atTime("2000-06-08T00:00:00Z")
        .hasResponse("generic_success_response.xml");
    // Verify new nameserver was added.
    assertThatCommand("domain_info_fakesite.xml")
        .atTime("2000-06-08T00:01:00Z")
        .hasResponse("domain_info_response_fakesite_3_nameservers.xml");
    // Verify that nameserver's data was set correctly.
    assertThatCommand("host_info_fakesite.xml")
        .atTime("2000-06-08T00:02:00Z")
        .hasResponse("host_info_response_fakesite_linked.xml");
  }

  /** Makes a one-time billing event corresponding to the given domain's creation. */
  protected static BillingEvent.OneTime makeOneTimeCreateBillingEvent(
      DomainBase domain, DateTime createTime) {
    return new BillingEvent.OneTime.Builder()
        .setReason(Reason.CREATE)
        .setTargetId(domain.getDomainName())
        .setClientId(domain.getCurrentSponsorClientId())
        .setCost(Money.parse("USD 26.00"))
        .setPeriodYears(2)
        .setEventTime(createTime)
        .setBillingTime(createTime.plus(Registry.get(domain.getTld()).getAddGracePeriodLength()))
        .setParent(getOnlyHistoryEntryOfType(domain, Type.DOMAIN_CREATE, DomainHistory.class))
        .build();
  }

  /** Makes a one-time billing event corresponding to the given domain's renewal. */
  static BillingEvent.OneTime makeOneTimeRenewBillingEvent(DomainBase domain, DateTime renewTime) {
    return new BillingEvent.OneTime.Builder()
        .setReason(Reason.RENEW)
        .setTargetId(domain.getDomainName())
        .setClientId(domain.getCurrentSponsorClientId())
        .setCost(Money.parse("USD 33.00"))
        .setPeriodYears(3)
        .setEventTime(renewTime)
        .setBillingTime(renewTime.plus(Registry.get(domain.getTld()).getRenewGracePeriodLength()))
        .setParent(getOnlyHistoryEntryOfType(domain, Type.DOMAIN_RENEW, DomainHistory.class))
        .build();
  }

  /** Makes a recurring billing event corresponding to the given domain's creation. */
  static BillingEvent.Recurring makeRecurringCreateBillingEvent(
      DomainBase domain, DateTime eventTime, DateTime endTime) {
    return makeRecurringBillingEvent(
        domain,
        getOnlyHistoryEntryOfType(domain, Type.DOMAIN_CREATE, DomainHistory.class),
        eventTime,
        endTime);
  }

  /** Makes a recurring billing event corresponding to the given domain's renewal. */
  static BillingEvent.Recurring makeRecurringRenewBillingEvent(
      DomainBase domain, DateTime eventTime, DateTime endTime) {
    return makeRecurringBillingEvent(
        domain,
        getOnlyHistoryEntryOfType(domain, Type.DOMAIN_RENEW, DomainHistory.class),
        eventTime,
        endTime);
  }

  /** Makes a recurring billing event corresponding to the given history entry. */
  protected static BillingEvent.Recurring makeRecurringBillingEvent(
      DomainBase domain, DomainHistory historyEntry, DateTime eventTime, DateTime endTime) {
    return new BillingEvent.Recurring.Builder()
        .setReason(Reason.RENEW)
        .setFlags(ImmutableSet.of(Flag.AUTO_RENEW))
        .setTargetId(domain.getDomainName())
        .setClientId(domain.getCurrentSponsorClientId())
        .setEventTime(eventTime)
        .setRecurrenceEndTime(endTime)
        .setParent(historyEntry)
        .build();
  }

  /** Makes a cancellation billing event cancelling out the given domain create billing event. */
  static BillingEvent.Cancellation makeCancellationBillingEventForCreate(
      DomainBase domain, OneTime billingEventToCancel, DateTime createTime, DateTime deleteTime) {
    return new BillingEvent.Cancellation.Builder()
        .setTargetId(domain.getDomainName())
        .setClientId(domain.getCurrentSponsorClientId())
        .setEventTime(deleteTime)
        .setOneTimeEventKey(VKey.from(findKeyToActualOneTimeBillingEvent(billingEventToCancel)))
        .setBillingTime(createTime.plus(Registry.get(domain.getTld()).getAddGracePeriodLength()))
        .setReason(Reason.CREATE)
        .setParent(getOnlyHistoryEntryOfType(domain, Type.DOMAIN_DELETE, DomainHistory.class))
        .build();
  }

  /** Makes a cancellation billing event cancelling out the given domain renew billing event. */
  static BillingEvent.Cancellation makeCancellationBillingEventForRenew(
      DomainBase domain, OneTime billingEventToCancel, DateTime renewTime, DateTime deleteTime) {
    return new BillingEvent.Cancellation.Builder()
        .setTargetId(domain.getDomainName())
        .setClientId(domain.getCurrentSponsorClientId())
        .setEventTime(deleteTime)
        .setOneTimeEventKey(VKey.from(findKeyToActualOneTimeBillingEvent(billingEventToCancel)))
        .setBillingTime(renewTime.plus(Registry.get(domain.getTld()).getRenewGracePeriodLength()))
        .setReason(Reason.RENEW)
        .setParent(getOnlyHistoryEntryOfType(domain, Type.DOMAIN_DELETE, DomainHistory.class))
        .build();
  }

  /**
   * Finds the Key to the actual one-time create billing event associated with a domain's creation.
   *
   * <p>This is used in the situation where we have created an expected billing event associated
   * with the domain's creation (which is passed as the parameter here), then need to locate the key
   * to the actual billing event in Datastore that would be seen on a Cancellation billing event.
   * This is necessary because the ID will be different even though all the rest of the fields are
   * the same.
   */
  private static Key<OneTime> findKeyToActualOneTimeBillingEvent(OneTime expectedBillingEvent) {
    Optional<OneTime> actualCreateBillingEvent =
        loadAllOf(BillingEvent.OneTime.class).stream()
            .filter(
                b ->
                    Objects.equals(
                        stripBillingEventId(b), stripBillingEventId(expectedBillingEvent)))
            .findFirst();
    assertThat(actualCreateBillingEvent).isPresent();
    return Key.create(actualCreateBillingEvent.get());
  }
}
