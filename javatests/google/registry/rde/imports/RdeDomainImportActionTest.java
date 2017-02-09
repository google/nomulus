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

package google.registry.rde.imports;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.model.ofy.ObjectifyService.ofy;
import static google.registry.testing.DatastoreHelper.createTld;
import static google.registry.testing.DatastoreHelper.getHistoryEntries;
import static google.registry.testing.DatastoreHelper.getPollMessages;
import static google.registry.testing.DatastoreHelper.newDomainResource;
import static google.registry.testing.DatastoreHelper.persistActiveContact;
import static google.registry.testing.DatastoreHelper.persistResource;
import static google.registry.testing.DatastoreHelper.persistSimpleResource;
import static google.registry.testing.TaskQueueHelper.assertDnsTasksEnqueued;
import static google.registry.util.DateTimeUtils.END_OF_TIME;
import static org.joda.money.CurrencyUnit.USD;
import static org.junit.Assert.fail;

import com.google.appengine.tools.cloudstorage.GcsFilename;
import com.google.appengine.tools.cloudstorage.GcsService;
import com.google.appengine.tools.cloudstorage.GcsServiceFactory;
import com.google.appengine.tools.cloudstorage.RetryParams;
import com.google.common.base.Optional;
import com.google.common.io.ByteSource;
import com.google.common.io.ByteStreams;
import com.googlecode.objectify.Key;
import google.registry.config.RegistryConfig.ConfigModule;
import google.registry.gcs.GcsUtils;
import google.registry.mapreduce.MapreduceRunner;
import google.registry.model.billing.BillingEvent;
import google.registry.model.domain.DomainResource;
import google.registry.model.domain.rgp.GracePeriodStatus;
import google.registry.model.eppcommon.Trid;
import google.registry.model.poll.PollMessage;
import google.registry.model.reporting.HistoryEntry;
import google.registry.model.transfer.TransferStatus;
import google.registry.request.Response;
import google.registry.testing.FakeResponse;
import google.registry.testing.mapreduce.MapreduceTestCase;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import org.joda.money.Money;
import org.joda.time.DateTime;
import org.joda.time.Seconds;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.runners.MockitoJUnitRunner;

/** Unit tests for {@link RdeDomainImportAction}. */
@RunWith(MockitoJUnitRunner.class)
public class RdeDomainImportActionTest extends MapreduceTestCase<RdeDomainImportAction> {

  private static final ByteSource DEPOSIT_1_DOMAIN = RdeImportsTestData.get("deposit_1_domain.xml");
  private static final ByteSource DEPOSIT_1_DOMAIN_PENDING_TRANSFER =
      RdeImportsTestData.get("deposit_1_domain_pending_transfer.xml");
  private static final String IMPORT_BUCKET_NAME = "import-bucket";
  private static final String IMPORT_FILE_NAME = "escrow-file.xml";

  private static final GcsService GCS_SERVICE =
      GcsServiceFactory.createGcsService(RetryParams.getDefaultInstance());

  private MapreduceRunner mrRunner;

  private Response response;

  @Before
  public void before() throws Exception {
    createTld("test");
    persistActiveContact("jd1234");
    persistActiveContact("sh8013");
    response = new FakeResponse();
    mrRunner = makeDefaultRunner();
    action = new RdeDomainImportAction(
        mrRunner,
        response,
        IMPORT_BUCKET_NAME,
        IMPORT_FILE_NAME,
        Optional.<Integer>of(3));
  }

  @Test
  public void testMapreduceSuccessfullyImportsDomain() throws Exception {
    pushToGcs(DEPOSIT_1_DOMAIN);
    runMapreduce();
    List<DomainResource> domains = ofy().load().type(DomainResource.class).list();
    assertThat(domains).hasSize(1);
    checkDomain(domains.get(0));
  }

  @Test
  public void testMapreduceSuccessfullyCreatesHistoryEntry() throws Exception {
    pushToGcs(DEPOSIT_1_DOMAIN);
    runMapreduce();
    List<DomainResource> domains = ofy().load().type(DomainResource.class).list();
    DomainResource domain = domains.get(0);
    // verify history entry
    List<HistoryEntry> historyEntries = getHistoryEntries(domain);
    assertThat(historyEntries).hasSize(1);
    checkHistoryEntry(historyEntries.get(0), domain);
  }

  /** Ensures that a second pass on a domain does not import a new domain. */
  @Test
  public void testMapreduceTwiceDoesNotDuplicateResources() throws Exception {
    pushToGcs(DEPOSIT_1_DOMAIN);
    // Create domain and history entry first
    DomainResource existingDomain =
        persistResource(
            newDomainResource("example1.test").asBuilder().setRepoId("Dexample1-TEST").build());
    persistSimpleResource(createHistoryEntry(
        existingDomain.getRepoId(),
        existingDomain.getCurrentSponsorClientId(),
        loadDomainXml(DEPOSIT_1_DOMAIN)));
    // Simulate running a second import and verify that the resources
    // aren't imported twice (only one domain, and one history entry)
    pushToGcs(DEPOSIT_1_DOMAIN);
    runMapreduce();
    List<DomainResource> domains = ofy().load().type(DomainResource.class).list();
    assertThat(domains.size()).isEqualTo(1);
    DomainResource domain = domains.get(0);
    // verify history entry
    List<HistoryEntry> historyEntries = getHistoryEntries(domain);
    assertThat(historyEntries).hasSize(1);
    checkHistoryEntry(historyEntries.get(0), domain);
  }

  /** Ensures that DNS publishing is kicked off on domain import */
  @Test
  public void test_mapreducePublishesToDns() throws Exception {
    pushToGcs(DEPOSIT_1_DOMAIN);
    runMapreduce();
    assertDnsTasksEnqueued("example1.test");
  }

  /**
   * Verifies the state of an imported pending transfer before and after implicit server approval
   */
  @Test
  public void testMapreducePendingTransferServerApproval() throws Exception {
    pushToGcs(DEPOSIT_1_DOMAIN_PENDING_TRANSFER);
    runMapreduce();
    List<DomainResource> domains = ofy().load().type(DomainResource.class).list();
    assertThat(domains).hasSize(1);
    checkDomain(domains.get(0));
    // implicit server approval happens at 2015-01-08T22:00:00.0Z
    DateTime serverApprovalTime = DateTime.parse("2015-01-08T22:00:00.0Z");
    // Domain should be assigned to RegistrarX before server approval
    DomainResource beforeApproval =
        domains.get(0).cloneProjectedAtTime(serverApprovalTime.minus(Seconds.ONE));
    assertThat(beforeApproval.getCurrentSponsorClientId()).isEqualTo("RegistrarX");
    assertThat(loadAutorenewBillingEventForDomain(beforeApproval).getClientId())
        .isEqualTo("RegistrarX");
    assertThat(loadAutorenewPollMessageForDomain(beforeApproval).getClientId())
        .isEqualTo("RegistrarX");
    // Current expiration is 2015-04-03T22:00:00.0Z
    assertThat(beforeApproval.getRegistrationExpirationTime())
        .isEqualTo(DateTime.parse("2015-04-03T22:00:00.0Z"));
    // Domain is not yet in transfer grace period
    assertThat(beforeApproval.getGracePeriodStatuses()).doesNotContain(GracePeriodStatus.TRANSFER);
    // Check autorenew events - recurrence end is set to transfer approval time,
    // and client id is set to losing registrar. This is important in case the
    // transfer is cancelled or rejected.
    // Event time is set to domain expiration date.
    checkAutorenewBillingEvent(beforeApproval,
        "RegistrarX",
        DateTime.parse("2015-04-03T22:00:00.0Z"),
        DateTime.parse("2015-01-08T22:00:00.0Z"));
    checkAutorenewPollMessage(beforeApproval,
        "RegistrarX",
        DateTime.parse("2015-04-03T22:00:00.0Z"),
        DateTime.parse("2015-01-08T22:00:00.0Z"));

    // Domain should be assigned to RegistrarY after server approval
    DomainResource afterApproval =
        domains.get(0).cloneProjectedAtTime(serverApprovalTime.plus(Seconds.ONE));
    assertThat(afterApproval.getCurrentSponsorClientId()).isEqualTo("RegistrarY");
    assertThat(loadAutorenewBillingEventForDomain(afterApproval).getClientId())
        .isEqualTo("RegistrarY");
    assertThat(loadAutorenewPollMessageForDomain(afterApproval).getClientId())
        .isEqualTo("RegistrarY");
    // New expiration should be incremented by 1 year
    assertThat(afterApproval.getRegistrationExpirationTime())
        .isEqualTo(DateTime.parse("2016-04-03T22:00:00.0Z"));
    // Domain should now be in transfer grace period
    assertThat(afterApproval.getGracePeriodStatuses()).contains(GracePeriodStatus.TRANSFER);
    // Check autorenew events - recurrence end is set to END_OF_TIME,
    // and client id is set to gaining registrar. This represents the new state of the domain,
    // unless the transfer is cancelled during the grace period, at which time it will
    // revert to the previous state.
    // Event time is set to domain expiration date.
    checkAutorenewBillingEvent(afterApproval,
        "RegistrarY",
        DateTime.parse("2016-04-03T22:00:00.0Z"),
        END_OF_TIME);
    checkAutorenewPollMessage(afterApproval,
        "RegistrarY",
        DateTime.parse("2016-04-03T22:00:00.0Z"),
        END_OF_TIME);
  }

  @Test
  public void testMapreducePendingTransferEvents() throws Exception {
    pushToGcs(DEPOSIT_1_DOMAIN_PENDING_TRANSFER);
    runMapreduce();
    List<DomainResource> domains = ofy().load().type(DomainResource.class).list();
    assertThat(domains).hasSize(1);
    checkDomain(domains.get(0));
    checkTransferRequestPollMessage(domains.get(0), "RegistrarX",
        DateTime.parse("2015-01-03T22:00:00.0Z"));
    checkTransferServerApprovalPollMessage(domains.get(0), "RegistrarX",
        DateTime.parse("2015-01-08T22:00:00.0Z"));
    checkTransferServerApprovalPollMessage(domains.get(0), "RegistrarY",
        DateTime.parse("2015-01-08T22:00:00.0Z"));
    // Billing event is set to the end of the transfer grace period, 5 days after server approval
    checkTransferBillingEvent(domains.get(0), DateTime.parse("2015-01-13T22:00:00.0Z"));
  }

  private void checkTransferBillingEvent(DomainResource domain, DateTime automaticTransferTime) {
    for (BillingEvent.OneTime event : ofy().load().type(BillingEvent.OneTime.class).ancestor(domain)
        .list()) {
      if (event.getReason() == BillingEvent.Reason.TRANSFER) {
        assertThat(event.getCost()).isEqualTo(Money.of(USD, 11));
        assertThat(event.getClientId()).isEqualTo("RegistrarY");
        assertThat(event.getBillingTime()).isEqualTo(automaticTransferTime);
      }
    }
  }

  /** Verifies the existence of a transfer request poll message */
  private void checkTransferRequestPollMessage(
      DomainResource domain, String clientId, DateTime expectedAt) {
    for (PollMessage message : getPollMessages(domain)) {
      if (TransferStatus.PENDING.getMessage().equals(message.getMsg())
          && clientId.equals(message.getClientId())
          && expectedAt.equals(message.getEventTime())) {
        return;
      }
    }
    fail("Expected transfer request poll message");
  }

  /** Verifies the existence of a transfer request poll message */
  private void checkTransferServerApprovalPollMessage(DomainResource domain,
      String clientId,
      DateTime expectedAt) {
    for (PollMessage message : getPollMessages(domain)) {
      if (TransferStatus.SERVER_APPROVED.getMessage().equals(message.getMsg())
          && clientId.equals(message.getClientId())
          && expectedAt.equals(message.getEventTime())) {
        return;
      }
    }
    fail("Expected transfer request poll message");
  }

  /** Verifies autorenew {@link PollMessage} is correct */
  private void checkAutorenewPollMessage(DomainResource domain,
      String clientId,
      DateTime expectedAt,
      DateTime recurrenceEndTime) {
    PollMessage.Autorenew autorenewPollMessage = loadAutorenewPollMessageForDomain(domain);
    assertThat(autorenewPollMessage).isNotNull();
    assertThat(autorenewPollMessage.getClientId()).isEqualTo(clientId);
    assertThat(autorenewPollMessage.getEventTime()).isEqualTo(expectedAt);
    assertThat(autorenewPollMessage.getAutorenewEndTime()).isEqualTo(recurrenceEndTime);
  }

  /** Verifies autorenew {@link BillingEvent} is correct */
  private void checkAutorenewBillingEvent(DomainResource domain,
      String clientId,
      DateTime expectedAt,
      DateTime recurrenceEndTime) {
    BillingEvent.Recurring autorenewBillingEvent = loadAutorenewBillingEventForDomain(domain);
    assertThat(autorenewBillingEvent).isNotNull();
    assertThat(autorenewBillingEvent.getClientId()).isEqualTo(clientId);
    assertThat(autorenewBillingEvent.getEventTime()).isEqualTo(expectedAt);
    assertThat(autorenewBillingEvent.getRecurrenceEndTime()).isEqualTo(recurrenceEndTime);
  }

  /** Verify history entry fields are correct */
  private void checkHistoryEntry(HistoryEntry entry, DomainResource parent) {
    assertThat(entry.getType()).isEqualTo(HistoryEntry.Type.RDE_IMPORT);
    assertThat(entry.getClientId()).isEqualTo(parent.getCurrentSponsorClientId());
    assertThat(entry.getXmlBytes().length).isGreaterThan(0);
    assertThat(entry.getBySuperuser()).isTrue();
    assertThat(entry.getReason()).isEqualTo("RDE Import");
    assertThat(entry.getRequestedByRegistrar()).isEqualTo(false);
    assertThat(entry.getParent()).isEqualTo(Key.create(parent));
  }

  /** Verifies that domain fields match expected values */
  private void checkDomain(DomainResource domain) {
    assertThat(domain.getFullyQualifiedDomainName()).isEqualTo("example1.test");
    assertThat(domain.getRepoId()).isEqualTo("Dexample1-TEST");
  }

  private void runMapreduce() throws Exception {
    action.run();
    executeTasksUntilEmpty("mapreduce");
  }

  private void pushToGcs(ByteSource source) throws IOException {
    try (OutputStream outStream =
            new GcsUtils(GCS_SERVICE, ConfigModule.provideGcsBufferSize())
                .openOutputStream(new GcsFilename(IMPORT_BUCKET_NAME, IMPORT_FILE_NAME));
        InputStream inStream = source.openStream()) {
      ByteStreams.copy(inStream, outStream);
    }
  }

  private static byte[] loadDomainXml(ByteSource source) throws IOException {
    byte[] result = new byte[((int) source.size())];
    try (InputStream inStream = source.openStream()) {
      ByteStreams.readFully(inStream, result);
    }
    return result;
  }

  private static BillingEvent.Recurring loadAutorenewBillingEventForDomain(DomainResource domain) {
    return ofy().load().key(domain.getAutorenewBillingEvent()).now();
  }

  private static PollMessage.Autorenew loadAutorenewPollMessageForDomain(DomainResource domain) {
    return ofy().load().key(domain.getAutorenewPollMessage()).now();
  }

  private static HistoryEntry createHistoryEntry(String roid, String clid, byte[] objectXml) {
    return new HistoryEntry.Builder()
        .setType(HistoryEntry.Type.RDE_IMPORT)
        .setClientId(clid)
        .setTrid(Trid.create(null))
        .setModificationTime(DateTime.now())
        .setXmlBytes(objectXml)
        .setBySuperuser(true)
        .setReason("RDE Import")
        .setRequestedByRegistrar(false)
        .setParent(Key.create(null, DomainResource.class, roid))
        .build();
  }
}
