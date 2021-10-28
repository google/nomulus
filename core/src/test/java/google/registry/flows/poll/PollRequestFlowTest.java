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

package google.registry.flows.poll;

import static google.registry.testing.DatabaseHelper.createHistoryEntryForEppResource;
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.newDomainBase;
import static google.registry.testing.DatabaseHelper.persistActiveContact;
import static google.registry.testing.DatabaseHelper.persistActiveHost;
import static google.registry.testing.DatabaseHelper.persistNewRegistrar;
import static google.registry.testing.DatabaseHelper.persistResource;
import static google.registry.testing.EppExceptionSubject.assertAboutEppExceptions;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.common.collect.ImmutableList;
import google.registry.flows.EppException;
import google.registry.flows.FlowTestCase;
import google.registry.flows.poll.PollRequestFlow.UnexpectedMessageIdException;
import google.registry.model.contact.ContactHistory;
import google.registry.model.contact.ContactResource;
import google.registry.model.domain.DomainBase;
import google.registry.model.eppcommon.Trid;
import google.registry.model.host.HostHistory;
import google.registry.model.host.HostResource;
import google.registry.model.poll.PendingActionNotificationResponse.DomainPendingActionNotificationResponse;
import google.registry.model.poll.PollMessage;
import google.registry.model.reporting.HistoryEntry;
import google.registry.model.transfer.TransferResponse.ContactTransferResponse;
import google.registry.model.transfer.TransferResponse.DomainTransferResponse;
import google.registry.model.transfer.TransferStatus;
import google.registry.testing.DualDatabaseTest;
import google.registry.testing.ReplayExtension;
import google.registry.testing.SetClockExtension;
import google.registry.testing.TestOfyAndSql;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link PollRequestFlow}. */
@DualDatabaseTest
class PollRequestFlowTest extends FlowTestCase<PollRequestFlow> {

  @Order(value = Order.DEFAULT - 3)
  @RegisterExtension
  final SetClockExtension setClockExtension = new SetClockExtension(clock, "2011-01-02T01:01:01Z");

  @Order(value = Order.DEFAULT - 2)
  @RegisterExtension
  final ReplayExtension replayExtension = ReplayExtension.createWithDoubleReplay(clock);

  private DomainBase domain;
  private ContactResource contact;
  private HostResource host;

  @BeforeEach
  void setUp() {
    setEppInput("poll.xml");
    setRegistrarIdForFlow("NewRegistrar");
    createTld("example");
    persistNewRegistrar("BadRegistrar");
    contact = persistActiveContact("jd1234");
    domain = persistResource(newDomainBase("test.example", contact));
    host = persistActiveHost("ns1.test.example");
  }

  private void persistPendingTransferPollMessage() {
    persistResource(
        new PollMessage.OneTime.Builder()
            .setRegistrarId(getRegistrarIdForFlow())
            .setEventTime(clock.nowUtc().minusDays(1))
            .setMsg("Transfer approved.")
            .setResponseData(
                ImmutableList.of(
                    new DomainTransferResponse.Builder()
                        .setFullyQualifiedDomainName("test.example")
                        .setTransferStatus(TransferStatus.SERVER_APPROVED)
                        .setGainingRegistrarId(getRegistrarIdForFlow())
                        .setTransferRequestTime(clock.nowUtc().minusDays(5))
                        .setLosingRegistrarId("TheRegistrar")
                        .setPendingTransferExpirationTime(clock.nowUtc().minusDays(1))
                        .setExtendedRegistrationExpirationTime(clock.nowUtc().plusYears(1))
                        .build()))
            .setParent(createHistoryEntryForEppResource(domain))
            .build());
  }

  @TestOfyAndSql
  void testSuccess_domainTransferApproved() throws Exception {
    persistPendingTransferPollMessage();
    assertTransactionalFlow(false);
    runFlowAssertResponse(loadFile("poll_response_domain_transfer.xml"));
  }

  @TestOfyAndSql
  void testSuccess_clTridNotSpecified() throws Exception {
    setEppInput("poll_no_cltrid.xml");
    persistPendingTransferPollMessage();
    assertTransactionalFlow(false);
    runFlowAssertResponse(loadFile("poll_response_domain_transfer_no_cltrid.xml"));
  }

  @TestOfyAndSql
  void testSuccess_contactTransferPending() throws Exception {
    setRegistrarIdForFlow("TheRegistrar");
    persistResource(
        new PollMessage.OneTime.Builder()
            .setId(3L)
            .setRegistrarId(getRegistrarIdForFlow())
            .setEventTime(clock.nowUtc().minusDays(5))
            .setMsg("Transfer requested.")
            .setResponseData(
                ImmutableList.of(
                    new ContactTransferResponse.Builder()
                        .setContactId("sh8013")
                        .setTransferStatus(TransferStatus.PENDING)
                        .setGainingRegistrarId(getRegistrarIdForFlow())
                        .setTransferRequestTime(clock.nowUtc().minusDays(5))
                        .setLosingRegistrarId("NewRegistrar")
                        .setPendingTransferExpirationTime(clock.nowUtc())
                        .build()))
            .setParent(createHistoryEntryForEppResource(contact))
            .build());
    assertTransactionalFlow(false);
    runFlowAssertResponse(loadFile("poll_response_contact_transfer.xml"));
  }

  @TestOfyAndSql
  void testSuccess_domainPendingActionComplete() throws Exception {
    persistResource(
        new PollMessage.OneTime.Builder()
            .setRegistrarId(getRegistrarIdForFlow())
            .setEventTime(clock.nowUtc().minusDays(1))
            .setMsg("Domain deleted.")
            .setResponseData(
                ImmutableList.of(
                    DomainPendingActionNotificationResponse.create(
                        "test.example",
                        true,
                        Trid.create("ABC-12345", "other-trid"),
                        clock.nowUtc())))
            .setParent(createHistoryEntryForEppResource(domain))
            .build());
    assertTransactionalFlow(false);
    runFlowAssertResponse(loadFile("poll_response_domain_pending_notification.xml"));
  }

  @TestOfyAndSql
  void testSuccess_domainPendingActionImmediateDelete() throws Exception {
    persistResource(
        new PollMessage.OneTime.Builder()
            .setRegistrarId(getRegistrarIdForFlow())
            .setEventTime(clock.nowUtc())
            .setMsg(
                String.format(
                    "Domain %s was deleted by registry administrator with final deletion"
                        + " effective: %s",
                    domain.getDomainName(), clock.nowUtc().minusMinutes(5)))
            .setResponseData(
                ImmutableList.of(
                    DomainPendingActionNotificationResponse.create(
                        domain.getDomainName(),
                        true,
                        Trid.create("ABC-12345", "other-trid"),
                        clock.nowUtc())))
            .setParent(createHistoryEntryForEppResource(domain))
            .build());
    assertTransactionalFlow(false);
    runFlowAssertResponse(loadFile("poll_message_domain_pending_action_immediate_delete.xml"));
  }

  @TestOfyAndSql
  void testSuccess_domainAutorenewMessage() throws Exception {
    persistResource(
        new PollMessage.Autorenew.Builder()
            .setRegistrarId(getRegistrarIdForFlow())
            .setEventTime(clock.nowUtc().minusDays(1))
            .setMsg("Domain was auto-renewed.")
            .setTargetId("test.example")
            .setParent(createHistoryEntryForEppResource(domain))
            .build());
    assertTransactionalFlow(false);
    runFlowAssertResponse(loadFile("poll_response_autorenew.xml"));
  }

  @TestOfyAndSql
  void testSuccess_empty() throws Exception {
    runFlowAssertResponse(loadFile("poll_response_empty.xml"));
  }

  @TestOfyAndSql
  void testSuccess_wrongRegistrar() throws Exception {
    persistResource(
        new PollMessage.OneTime.Builder()
            .setRegistrarId("BadRegistrar")
            .setEventTime(clock.nowUtc().minusDays(1))
            .setMsg("Poll message")
            .setParent(createHistoryEntryForEppResource(domain))
            .build());
    runFlowAssertResponse(loadFile("poll_response_empty.xml"));
  }

  @TestOfyAndSql
  void testSuccess_futurePollMessage() throws Exception {
    persistResource(
        new PollMessage.OneTime.Builder()
            .setRegistrarId(getRegistrarIdForFlow())
            .setEventTime(clock.nowUtc().plusDays(1))
            .setMsg("Poll message")
            .setParent(createHistoryEntryForEppResource(domain))
            .build());
    runFlowAssertResponse(loadFile("poll_response_empty.xml"));
  }

  @TestOfyAndSql
  void testSuccess_futureAutorenew() throws Exception {
    persistResource(
        new PollMessage.Autorenew.Builder()
            .setRegistrarId(getRegistrarIdForFlow())
            .setEventTime(clock.nowUtc().plusDays(1))
            .setMsg("Domain was auto-renewed.")
            .setTargetId("target.example")
            .setParent(createHistoryEntryForEppResource(domain))
            .build());
    assertTransactionalFlow(false);
    runFlowAssertResponse(loadFile("poll_response_empty.xml"));
  }

  @TestOfyAndSql
  void testSuccess_contactDelete() throws Exception {
    // Contact delete poll messages do not have any response data, so ensure that no
    // response data block is produced in the poll message.
    HistoryEntry historyEntry =
        persistResource(
            new ContactHistory.Builder()
                .setRegistrarId("NewRegistrar")
                .setModificationTime(clock.nowUtc().minusDays(1))
                .setType(HistoryEntry.Type.CONTACT_DELETE)
                .setContact(contact)
                .build());
    persistResource(
        new PollMessage.OneTime.Builder()
            .setRegistrarId("NewRegistrar")
            .setMsg("Deleted contact jd1234")
            .setParent(historyEntry)
            .setEventTime(clock.nowUtc().minusDays(1))
            .build());
    assertTransactionalFlow(false);
    runFlowAssertResponse(loadFile("poll_response_contact_delete.xml"));
  }

  @TestOfyAndSql
  void testSuccess_hostDelete() throws Exception {
    // Host delete poll messages do not have any response data, so ensure that no
    // response data block is produced in the poll message.
    HistoryEntry historyEntry =
        persistResource(
            new HostHistory.Builder()
                .setRegistrarId("NewRegistrar")
                .setModificationTime(clock.nowUtc().minusDays(1))
                .setType(HistoryEntry.Type.HOST_DELETE)
                .setHost(host)
                .build());
    persistResource(
        new PollMessage.OneTime.Builder()
            .setRegistrarId("NewRegistrar")
            .setMsg("Deleted host ns1.test.example")
            .setParent(historyEntry)
            .setEventTime(clock.nowUtc().minusDays(1))
            .build());
    clock.advanceOneMilli();
    assertTransactionalFlow(false);
    runFlowAssertResponse(loadFile("poll_response_host_delete.xml"));
  }

  @TestOfyAndSql
  void testFailure_messageIdProvided() throws Exception {
    setEppInput("poll_with_id.xml");
    assertTransactionalFlow(false);
    EppException thrown = assertThrows(UnexpectedMessageIdException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }
}
