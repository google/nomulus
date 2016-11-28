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

package google.registry.flows.domain;

import static com.google.common.io.BaseEncoding.base16;
import static com.google.common.truth.Truth.assertThat;
import static google.registry.model.EppResourceUtils.loadDomainApplication;
import static google.registry.model.ofy.ObjectifyService.ofy;
import static google.registry.testing.DatastoreHelper.assertBillingEvents;
import static google.registry.testing.DatastoreHelper.createTld;
import static google.registry.testing.DatastoreHelper.getOnlyHistoryEntryOfType;
import static google.registry.testing.DatastoreHelper.newDomainApplication;
import static google.registry.testing.DatastoreHelper.persistActiveContact;
import static google.registry.testing.DatastoreHelper.persistActiveDomain;
import static google.registry.testing.DatastoreHelper.persistActiveHost;
import static google.registry.testing.DatastoreHelper.persistDeletedDomain;
import static google.registry.testing.DatastoreHelper.persistReservedList;
import static google.registry.testing.DatastoreHelper.persistResource;
import static google.registry.testing.DomainApplicationSubject.assertAboutApplications;
import static google.registry.testing.DomainResourceSubject.assertAboutDomains;
import static google.registry.testing.TaskQueueHelper.assertDnsTasksEnqueued;
import static google.registry.testing.TaskQueueHelper.assertNoDnsTasksEnqueued;
import static google.registry.testing.TaskQueueHelper.assertNoTasksEnqueued;
import static google.registry.testing.TaskQueueHelper.assertTasksEnqueued;
import static google.registry.util.DateTimeUtils.END_OF_TIME;
import static org.joda.money.CurrencyUnit.USD;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.googlecode.objectify.Key;
import google.registry.flows.ResourceFlowTestCase;
import google.registry.flows.domain.DomainAllocateFlow.HasFinalStatusException;
import google.registry.flows.domain.DomainAllocateFlow.MissingApplicationException;
import google.registry.flows.domain.DomainAllocateFlow.OnlySuperuserCanAllocateException;
import google.registry.flows.exceptions.ResourceAlreadyExistsException;
import google.registry.model.billing.BillingEvent;
import google.registry.model.billing.BillingEvent.Flag;
import google.registry.model.billing.BillingEvent.Reason;
import google.registry.model.domain.DomainApplication;
import google.registry.model.domain.DomainResource;
import google.registry.model.domain.GracePeriod;
import google.registry.model.domain.TestExtraLogicManager;
import google.registry.model.domain.TestExtraLogicManager.TestExtraLogicManagerSuccessException;
import google.registry.model.domain.launch.ApplicationStatus;
import google.registry.model.domain.launch.LaunchInfoResponseExtension;
import google.registry.model.domain.launch.LaunchNotice;
import google.registry.model.domain.launch.LaunchPhase;
import google.registry.model.domain.rgp.GracePeriodStatus;
import google.registry.model.domain.secdns.DelegationSignerData;
import google.registry.model.eppcommon.StatusValue;
import google.registry.model.eppcommon.Trid;
import google.registry.model.ofy.ObjectifyService;
import google.registry.model.poll.PendingActionNotificationResponse.DomainPendingActionNotificationResponse;
import google.registry.model.poll.PollMessage;
import google.registry.model.registry.Registry;
import google.registry.model.registry.Registry.TldState;
import google.registry.model.reporting.HistoryEntry;
import google.registry.model.smd.EncodedSignedMark;
import google.registry.testing.TaskQueueHelper.TaskMatcher;
import org.joda.money.Money;
import org.joda.time.DateTime;
import org.joda.time.Duration;
import org.junit.Before;
import org.junit.Test;

/** Unit tests for {@link DomainAllocateFlow}. */
public class DomainAllocateFlowTest
    extends ResourceFlowTestCase<DomainAllocateFlow, DomainResource> {

  // These constants come from "domain_allocate.xml" and its variants.
  private static final DateTime APPLICATION_TIME = DateTime.parse("2010-08-16T10:00:00.0Z");
  private static final String SMD_ID = "1-1";

  private static final String CLIENT_ID = "TheRegistrar";
  private static final Trid TRID = Trid.create("ABC-123");

  /** The applicationId, expressed as a base 10 String. */
  private String applicationId = "2-TLD";
  private DomainApplication application;
  private HistoryEntry historyEntry;

  @Before
  public void initAllocateTest() throws Exception {
    setEppInput(
        "domain_allocate.xml",
        ImmutableMap.of("APPLICATIONID", "2-TLD", "DOMAIN", "example-one.tld"));
    clock.setTo(APPLICATION_TIME);
  }

  private void setupDomainApplication(String tld, TldState tldState) throws Exception {
    createTld(tld, tldState);
    persistResource(Registry.get(tld).asBuilder().setReservedLists(persistReservedList(
        tld + "-reserved",
        "reserved-label,FULLY_BLOCKED",
        "collision-label,NAME_COLLISION")).build());
    String domainName = getUniqueIdFromCommand();
    application = persistResource(newDomainApplication(domainName).asBuilder()
        .setCreationTrid(TRID)
        .setEncodedSignedMarks(ImmutableList.of(EncodedSignedMark.create("base64", "abcdef")))
        .setCreationTrid(TRID)
        .build());
    for (int i = 1; i <= 14; ++i) {
      persistActiveHost(String.format("ns%d.example.net", i));
    }
    persistActiveContact("jd1234");
    persistActiveContact("sh8013");
    clock.setTo(DateTime.parse("2010-09-16T10:00:00.0Z"));
  }

  private void doSuccessfulTest(int nameservers) throws Exception {
    assertTransactionalFlow(true);
    runFlowAssertResponse(
        CommitMode.LIVE,
        UserPrivileges.SUPERUSER,
        readFile("domain_allocate_response.xml"));
    // Check that the domain was created and persisted with a history entry.
    DomainResource domain = reloadResourceByForeignKey();
    assertAboutDomains().that(domain)
        .hasOnlyOneHistoryEntryWhich()
        .hasType(HistoryEntry.Type.DOMAIN_ALLOCATE);
    historyEntry = getOnlyHistoryEntryOfType(domain, HistoryEntry.Type.DOMAIN_ALLOCATE);

    // The domain gets the sunrush add grace period if no nameservers were set during allocation.
    boolean sunrushAddGracePeriod = (nameservers == 0);

    // The application should be marked as allocated, with a new history entry.
    DomainApplication application = loadDomainApplication(applicationId, clock.nowUtc());
    assertAboutApplications().that(application)
        .hasApplicationStatus(ApplicationStatus.ALLOCATED).and()
        .hasHistoryEntryAtIndex(0)
            .which().hasType(HistoryEntry.Type.DOMAIN_APPLICATION_STATUS_UPDATE);

    String domainName = getUniqueIdFromCommand();
    // There should be a poll message for the allocated application (and one for generic autorenew).
    assertPollMessages(
        new PollMessage.OneTime.Builder()
            .setClientId(CLIENT_ID)
            .setEventTime(clock.nowUtc())
            .setMsg("Domain was allocated")
            .setResponseData(ImmutableList.of(DomainPendingActionNotificationResponse.create(
                domainName, true, TRID, clock.nowUtc())))
            .setResponseExtensions(ImmutableList.of(new LaunchInfoResponseExtension.Builder()
                .setApplicationId(applicationId)
                .setPhase(LaunchPhase.SUNRISE)  // This comes from newDomainApplication()
                .setApplicationStatus(ApplicationStatus.ALLOCATED)
                .build()))
            .setParent(historyEntry)
            .build(),
        new PollMessage.Autorenew.Builder()
            .setTargetId(domainName)
            .setClientId(CLIENT_ID)
            .setEventTime(clock.nowUtc().plusYears(2))
            .setMsg("Domain was auto-renewed.")
            .setParent(historyEntry)
            .build());

    // There should be a bill for the create and a recurring autorenew event.
    BillingEvent.OneTime createBillingEvent = new BillingEvent.OneTime.Builder()
        .setReason(Reason.CREATE)
        .setFlags(ImmutableSet.of(Flag.ALLOCATION, Flag.SUNRISE))
        .setTargetId(domainName)
        .setClientId(CLIENT_ID)
        .setCost(Money.of(USD, 26))
        .setPeriodYears(2)
        .setEventTime(clock.nowUtc())
        .setBillingTime(clock.nowUtc().plus(sunrushAddGracePeriod
            ? Registry.get("tld").getSunrushAddGracePeriodLength()
            : Registry.get("tld").getAddGracePeriodLength()))
        .setParent(historyEntry)
        .build();
    assertBillingEvents(
        createBillingEvent,
        new BillingEvent.Recurring.Builder()
            .setReason(Reason.RENEW)
            .setFlags(ImmutableSet.of(Flag.AUTO_RENEW))
            .setTargetId(domainName)
            .setClientId(CLIENT_ID)
            .setEventTime(domain.getRegistrationExpirationTime())
            .setRecurrenceEndTime(END_OF_TIME)
            .setParent(historyEntry)
            .build());
    assertGracePeriods(
        domain.getGracePeriods(),
        ImmutableMap.of(
            GracePeriod.create(
                sunrushAddGracePeriod ? GracePeriodStatus.SUNRUSH_ADD : GracePeriodStatus.ADD,
                clock.nowUtc().plus(sunrushAddGracePeriod
                    ? Registry.get("tld").getSunrushAddGracePeriodLength()
                    : Registry.get("tld").getAddGracePeriodLength()),
                CLIENT_ID,
                null),
            createBillingEvent));
    assertThat(ofy().load().key(domain.getAutorenewBillingEvent()).now().getEventTime())
        .isEqualTo(domain.getRegistrationExpirationTime());

    assertThat(domain.getApplicationTime()).isEqualTo(APPLICATION_TIME);
    assertThat(domain.getApplication()).isEqualTo(Key.create(application));
    if (nameservers == 0) {
      assertNoDnsTasksEnqueued();
    } else {
      assertDnsTasksEnqueued(domainName);
    }
  }

  private void runFlowAsSuperuser() throws Exception {
    assertTransactionalFlow(true);
    runFlow(CommitMode.LIVE, UserPrivileges.SUPERUSER);
  }

  @Test
  public void testSuccess() throws Exception {
    setupDomainApplication("tld", TldState.QUIET_PERIOD);
    doSuccessfulTest(2);
  }

  @Test
  public void testSuccess_sunrushAddGracePeriod() throws Exception {
    setupDomainApplication("tld", TldState.QUIET_PERIOD);
    setEppInput("domain_allocate_no_nameservers.xml");
    doSuccessfulTest(0);
  }

  @Test
  public void testSuccess_nonDefaultAddGracePeriod() throws Exception {
    setupDomainApplication("tld", TldState.QUIET_PERIOD);
    persistResource(Registry.get("tld").asBuilder()
        .setAddGracePeriodLength(Duration.standardMinutes(6))
        .build());
    doSuccessfulTest(2);
  }

  @Test
  public void testSuccess_nonDefaultSunrushAddGracePeriod() throws Exception {
    setupDomainApplication("tld", TldState.QUIET_PERIOD);
    persistResource(Registry.get("tld").asBuilder()
        .setSunrushAddGracePeriodLength(Duration.standardMinutes(9))
        .build());
    doSuccessfulTest(2);
  }

  @Test
  public void testSuccess_existedButWasDeleted() throws Exception {
    setupDomainApplication("tld", TldState.QUIET_PERIOD);
    persistDeletedDomain(getUniqueIdFromCommand(), clock.nowUtc().minusDays(1));
    clock.advanceOneMilli();
    doSuccessfulTest(2);
  }

  @Test
  public void testSuccess_maxNumberOfNameservers() throws Exception {
    setupDomainApplication("tld", TldState.QUIET_PERIOD);
    setEppInput("domain_allocate_13_nameservers.xml");
    doSuccessfulTest(13);
  }

  @Test
  @Override
  public void testRequiresLogin() throws Exception {
    createTld("tld");
    super.testRequiresLogin();
  }

  @Test
  public void testSuccess_secDns() throws Exception {
    setupDomainApplication("tld", TldState.QUIET_PERIOD);
    setEppInput("domain_allocate_dsdata.xml");
    doSuccessfulTest(2);
    assertAboutDomains().that(reloadResourceByForeignKey())
        .hasExactlyDsData(DelegationSignerData.create(
              12345, 3, 1, base16().decode("49FD46E6C4B45C55D4AC")));
  }

  @Test
  public void testSuccess_secDnsMaxRecords() throws Exception {
    setupDomainApplication("tld", TldState.QUIET_PERIOD);
    setEppInput("domain_allocate_dsdata_8_records.xml");
    doSuccessfulTest(2);
    assertThat(getOnlyGlobalResource(DomainResource.class)).isNotNull();
    assertThat(reloadResourceByForeignKey().getDsData()).hasSize(8);
  }

  @Test
  public void testSuccess_idn() throws Exception {
    setupDomainApplication("tld", TldState.QUIET_PERIOD);
    setEppInput("domain_allocate_idn.xml");
    clock.advanceOneMilli();
    runFlowAsSuperuser();
    assertThat(getOnlyGlobalResource(DomainResource.class)).isNotNull();
    assertDnsTasksEnqueued("xn--15qt0w.tld");
  }

  private void doSuccessfulClaimsNoticeTest() throws Exception {
    setEppInput("domain_allocate_claims_notice.xml");
    runFlowAsSuperuser();
    assertAboutDomains().that(getOnlyGlobalResource(DomainResource.class))
        .hasLaunchNotice(LaunchNotice.create(
            "370d0b7c9223372036854775807",
            "tmch",
            DateTime.parse("2011-08-16T09:00:00.0Z"),
            DateTime.parse("2010-07-16T09:00:00.0Z")));
  }

  @Test
  public void testSuccess_claimsNotice() throws Exception {
    setupDomainApplication("tld", TldState.QUIET_PERIOD);
    doSuccessfulClaimsNoticeTest();
    String expectedCsv = String.format(
        "%s,example-one.tld,370d0b7c9223372036854775807,1,"
        + "2010-09-16T10:00:00.000Z,2010-07-16T09:00:00.000Z,2010-08-16T10:00:00.000Z",
        reloadResourceByForeignKey().getRepoId());
    assertTasksEnqueued(
        "lordn-claims", new TaskMatcher().payload(expectedCsv).tag("tld"));
  }

  @Test
  public void testSuccess_expiredClaim() throws Exception {
    setupDomainApplication("tld", TldState.QUIET_PERIOD);
    clock.setTo(DateTime.parse("2011-08-17T09:00:00.0Z"));
    doSuccessfulClaimsNoticeTest();
    String expectedCsv = String.format(
        "%s,example-one.tld,370d0b7c9223372036854775807,1,"
        + "2011-08-17T09:00:00.000Z,2010-07-16T09:00:00.000Z,2010-08-16T10:00:00.000Z",
        reloadResourceByForeignKey().getRepoId());
    assertTasksEnqueued("lordn-claims", new TaskMatcher().payload(expectedCsv).tag("tld"));
  }

  @Test
  public void testSuccess_smdId() throws Exception {
    setupDomainApplication("tld", TldState.QUIET_PERIOD);
    setEppInput("domain_allocate_smd_id.xml");
    doSuccessfulTest(2);
    DomainResource domain = getOnlyGlobalResource(DomainResource.class);
    assertThat(domain.getSmdId()).isEqualTo(SMD_ID);
    String expectedCsv = String.format(
        "%s,example-one.tld,1-1,1,2010-09-16T10:00:00.000Z,2010-08-16T10:00:00.000Z",
        domain.getRepoId());
    assertTasksEnqueued(
        "lordn-sunrise", new TaskMatcher().payload(expectedCsv).tag("tld"));
  }

  @Test
  public void testSuccess_collision() throws Exception {
    setupDomainApplication("tld", TldState.QUIET_PERIOD);
    setEppInput("domain_allocate_collision.xml");
    assertNoDnsTasksEnqueued();
    runFlowAsSuperuser();
    assertAboutDomains()
        .that(getOnlyGlobalResource(DomainResource.class))
        .hasStatusValue(StatusValue.SERVER_HOLD);
    assertNoDnsTasksEnqueued();
  }

  @Test
  public void testSuccess_reserved() throws Exception {
    setupDomainApplication("tld", TldState.QUIET_PERIOD);
    setEppInput("domain_allocate_reserved.xml");
    runFlowAsSuperuser();
    assertThat(getOnlyGlobalResource(DomainResource.class)).isNotNull();
  }

  @Test
  public void testSuccess_premiumName() throws Exception {
    setEppInput("domain_allocate_premium.xml");
    setupDomainApplication("example", TldState.QUIET_PERIOD);
    persistResource(Registry.get("example").asBuilder().setPremiumPriceAckRequired(true).build());
    clock.advanceOneMilli();
    runFlowAsSuperuser();
  }

  @Test
  public void testSuccess_hexApplicationId() throws Exception {
    setEppInput(
        "domain_allocate.xml",
        ImmutableMap.of("APPLICATIONID", "A-TLD", "DOMAIN", "example-one.tld"));
    applicationId = "A-TLD";
    // Grab the next 8 ids so that when the application is created it gets dec 10, or hex A.
    // (one additional ID goes to the reserved list created before the application).
    for (int i = 1; i <= 8; i++) {
      ObjectifyService.allocateId();
    }
    setupDomainApplication("tld", TldState.QUIET_PERIOD);
    doSuccessfulTest(2);
  }

  @Test
  public void testFailure_expiredAcceptance() throws Exception {
    setupDomainApplication("tld", TldState.QUIET_PERIOD);
    doSuccessfulClaimsNoticeTest();
    assertNoTasksEnqueued("lordn-sunrise");
  }

  @Test
  public void testSuccess_missingClaimsNotice() throws Exception {
    setupDomainApplication("tld", TldState.QUIET_PERIOD);
    persistClaimsList(
        ImmutableMap.of("example-one", "2013041500/2/6/9/rJ1NrDO92vDsAzf7EQzgjX4R0000000001"));
    doSuccessfulTest(2);
  }

  @Test
  public void testFailure_alreadyExists() throws Exception {
    setupDomainApplication("tld", TldState.QUIET_PERIOD);
    persistActiveDomain(getUniqueIdFromCommand());
    thrown.expect(ResourceAlreadyExistsException.class);
    runFlowAsSuperuser();
  }

  @Test
  public void testSuccess_predelegation() throws Exception {
    setupDomainApplication("tld", TldState.QUIET_PERIOD);
    createTld("tld", TldState.PREDELEGATION);
    doSuccessfulTest(2);
  }

  @Test
  public void testSuccess_sunrise() throws Exception {
    setupDomainApplication("tld", TldState.QUIET_PERIOD);
    createTld("tld", TldState.SUNRISE);
    doSuccessfulTest(2);
  }

  @Test
  public void testSuccess_sunrush() throws Exception {
    setupDomainApplication("tld", TldState.QUIET_PERIOD);
    createTld("tld", TldState.SUNRUSH);
    doSuccessfulTest(2);
  }

  @Test
  public void testSucess_quietPeriod() throws Exception {
    setupDomainApplication("tld", TldState.QUIET_PERIOD);
    createTld("tld", TldState.QUIET_PERIOD);
    doSuccessfulTest(2);
  }

  @Test
  public void testFailure_applicationDeleted() throws Exception {
    setupDomainApplication("tld", TldState.QUIET_PERIOD);
    persistResource(application.asBuilder().setDeletionTime(clock.nowUtc()).build());
    thrown.expect(MissingApplicationException.class);
    runFlowAsSuperuser();
  }

  @Test
  public void testFailure_applicationRejected() throws Exception {
    setupDomainApplication("tld", TldState.QUIET_PERIOD);
    persistResource(application.asBuilder()
        .setApplicationStatus(ApplicationStatus.REJECTED)
        .build());
    thrown.expect(HasFinalStatusException.class);
    runFlowAsSuperuser();
  }

  @Test
  public void testFailure_applicationAllocated() throws Exception {
    setupDomainApplication("tld", TldState.QUIET_PERIOD);
    persistResource(application.asBuilder()
        .setApplicationStatus(ApplicationStatus.ALLOCATED)
        .build());
    thrown.expect(HasFinalStatusException.class);
    runFlowAsSuperuser();
  }

  @Test
  public void testFailure_applicationDoesNotExist() throws Exception {
    setupDomainApplication("tld", TldState.QUIET_PERIOD);
    setEppInput("domain_allocate_bad_application_roid.xml");
    thrown.expect(MissingApplicationException.class);
    runFlowAsSuperuser();
  }

  @Test
  public void testFailure_onlySuperuserCanAllocate() throws Exception {
    setupDomainApplication("tld", TldState.GENERAL_AVAILABILITY);
    clock.advanceOneMilli();
    setEppInput("domain_allocate_no_nameservers.xml");
    assertTransactionalFlow(true);
    thrown.expect(OnlySuperuserCanAllocateException.class);
    runFlow(CommitMode.LIVE, UserPrivileges.NORMAL);
  }

  @Test
  public void testSuccess_extra() throws Exception {
    setEppInput(
        "domain_allocate.xml",
        ImmutableMap.of("APPLICATIONID", "2-EXTRA", "DOMAIN", "domain.extra"));
    setupDomainApplication("extra", TldState.QUIET_PERIOD);
    RegistryExtraFlowLogicProxy.setOverride("extra", TestExtraLogicManager.class);
    thrown.expect(TestExtraLogicManagerSuccessException.class, "allocated");
    runFlowAsSuperuser();
  }
}
