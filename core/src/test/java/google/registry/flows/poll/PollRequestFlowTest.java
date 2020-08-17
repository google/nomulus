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

import static google.registry.testing.DatastoreHelper.createHistoryEntryForEppResource;
import static google.registry.testing.DatastoreHelper.createTld;
import static google.registry.testing.DatastoreHelper.newDomainBase;
import static google.registry.testing.DatastoreHelper.persistActiveContact;
import static google.registry.testing.DatastoreHelper.persistActiveHost;
import static google.registry.testing.DatastoreHelper.persistResource;
import static google.registry.testing.EppExceptionSubject.assertAboutEppExceptions;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.common.collect.ImmutableList;
import google.registry.flows.EppException;
import google.registry.flows.FlowTestCase;
import google.registry.flows.poll.PollRequestFlow.UnexpectedMessageIdException;
import google.registry.model.contact.ContactResource;
import google.registry.model.domain.DomainBase;
import google.registry.model.eppcommon.Trid;
import google.registry.model.host.HostResource;
import google.registry.model.poll.PendingActionNotificationResponse.DomainPendingActionNotificationResponse;
import google.registry.model.poll.PollMessage;
import google.registry.model.reporting.HistoryEntry;
import google.registry.model.transfer.TransferResponse.ContactTransferResponse;
import google.registry.model.transfer.TransferResponse.DomainTransferResponse;
import google.registry.model.transfer.TransferStatus;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link PollRequestFlow}. */
class PollRequestFlowTest extends FlowTestCase<PollRequestFlow> {

  private DomainBase domain;
  private ContactResource contact;
  private HostResource host;

  @BeforeEach
  void setUp() {
    setEppInput("poll.xml");
    setClientIdForFlow("NewRegistrar");
    clock.setTo(DateTime.parse("2011-01-02T01:01:01Z"));
    createTld("example");
    contact = persistActiveContact("jd1234");
    domain = persistResource(newDomainBase("test.example", contact));
    host = persistActiveHost("ns1.test.example");
  }

  private void persistPendingTransferPollMessage() {
    persistResource(
        new PollMessage.OneTime.Builder()
            .setClientId(getClientIdForFlow())
            .setEventTime(clock.nowUtc().minusDays(1))
            .setMsg("Transfer approved.")
            .setResponseData(
                ImmutableList.of(
                    new DomainTransferResponse.Builder()
                        .setFullyQualifiedDomainName("test.example")
                        .setTransferStatus(TransferStatus.SERVER_APPROVED)
                        .setGainingClientId(getClientIdForFlow())
                        .setTransferRequestTime(clock.nowUtc().minusDays(5))
                        .setLosingClientId("TheRegistrar")
                        .setPendingTransferExpirationTime(clock.nowUtc().minusDays(1))
                        .setExtendedRegistrationExpirationTime(clock.nowUtc().plusYears(1))
                        .build()))
            .setParent(createHistoryEntryForEppResource(domain))
            .build());
  }

  @Test
  void testSuccess_domainTransferApproved() throws Exception {
    persistPendingTransferPollMessage();
    assertTransactionalFlow(false);
    runFlowAssertResponse(loadFile("poll_response_domain_transfer.xml"));
  }

  @Test
  void testSuccess_clTridNotSpecified() throws Exception {
    setEppInput("poll_no_cltrid.xml");
    persistPendingTransferPollMessage();
    assertTransactionalFlow(false);
    runFlowAssertResponse(loadFile("poll_response_domain_transfer_no_cltrid.xml"));
  }

  @Test
  void testSuccess_contactTransferPending() throws Exception {
    clock.setTo(DateTime.parse("2000-06-13T22:00:00.0Z"));
    setClientIdForFlow("TheRegistrar");
    persistResource(
        new PollMessage.OneTime.Builder()
            .setId(3L)
            .setClientId(getClientIdForFlow())
            .setEventTime(clock.nowUtc().minusDays(5))
            .setMsg("Transfer requested.")
            .setResponseData(ImmutableList.of(new ContactTransferResponse.Builder()
                .setContactId("sh8013")
                .setTransferStatus(TransferStatus.PENDING)
                .setGainingClientId(getClientIdForFlow())
                .setTransferRequestTime(clock.nowUtc().minusDays(5))
                .setLosingClientId("NewRegistrar")
                .setPendingTransferExpirationTime(clock.nowUtc())
                .build()))
            .setParent(createHistoryEntryForEppResource(contact))
            .build());
    assertTransactionalFlow(false);
    runFlowAssertResponse(loadFile("poll_response_contact_transfer.xml"));
  }

  @Test
  void testSuccess_domainPendingActionComplete() throws Exception {
    persistResource(
        new PollMessage.OneTime.Builder()
            .setClientId(getClientIdForFlow())
            .setEventTime(clock.nowUtc().minusDays(1))
            .setMsg("Domain deleted.")
            .setResponseData(ImmutableList.of(DomainPendingActionNotificationResponse.create(
                "test.example", true, Trid.create("ABC-12345", "other-trid"), clock.nowUtc())))
            .setParent(createHistoryEntryForEppResource(domain))
            .build());
    assertTransactionalFlow(false);
    runFlowAssertResponse(loadFile("poll_response_domain_pending_notification.xml"));
  }

  @Test
  void testSuccess_domainAutorenewMessage() throws Exception {
    persistResource(
        new PollMessage.Autorenew.Builder()
            .setClientId(getClientIdForFlow())
            .setEventTime(clock.nowUtc().minusDays(1))
            .setMsg("Domain was auto-renewed.")
            .setTargetId("test.example")
            .setParent(createHistoryEntryForEppResource(domain))
            .build());
    assertTransactionalFlow(false);
    runFlowAssertResponse(loadFile("poll_response_autorenew.xml"));
  }

  @Test
  void testSuccess_empty() throws Exception {
    runFlowAssertResponse(loadFile("poll_response_empty.xml"));
  }

  @Test
  void testSuccess_wrongRegistrar() throws Exception {
    persistResource(
        new PollMessage.OneTime.Builder()
            .setClientId("different client id")
            .setEventTime(clock.nowUtc().minusDays(1))
            .setMsg("Poll message")
            .setParent(createHistoryEntryForEppResource(domain))
            .build());
    runFlowAssertResponse(loadFile("poll_response_empty.xml"));
  }

  @Test
  void testSuccess_futurePollMessage() throws Exception {
    persistResource(
        new PollMessage.OneTime.Builder()
            .setClientId(getClientIdForFlow())
            .setEventTime(clock.nowUtc().plusDays(1))
            .setMsg("Poll message")
            .setParent(createHistoryEntryForEppResource(domain))
            .build());
    runFlowAssertResponse(loadFile("poll_response_empty.xml"));
  }

  @Test
  void testSuccess_futureAutorenew() throws Exception {
    persistResource(
        new PollMessage.Autorenew.Builder()
            .setClientId(getClientIdForFlow())
            .setEventTime(clock.nowUtc().plusDays(1))
            .setMsg("Domain was auto-renewed.")
            .setTargetId("target.example")
            .setParent(createHistoryEntryForEppResource(domain))
            .build());
    assertTransactionalFlow(false);
    runFlowAssertResponse(loadFile("poll_response_empty.xml"));
  }

  @Test
  void testSuccess_contactDelete() throws Exception {
    // Contact delete poll messages do not have any response data, so ensure that no
    // response data block is produced in the poll message.
    HistoryEntry historyEntry = persistResource(new HistoryEntry.Builder()
        .setClientId("NewRegistrar")
        .setModificationTime(clock.nowUtc().minusDays(1))
        .setType(HistoryEntry.Type.CONTACT_DELETE)
        .setParent(contact)
        .build());
    persistResource(
        new PollMessage.OneTime.Builder()
            .setClientId("NewRegistrar")
            .setMsg("Deleted contact jd1234")
            .setParent(historyEntry)
            .setEventTime(clock.nowUtc().minusDays(1))
            .build());
    assertTransactionalFlow(false);
    runFlowAssertResponse(loadFile("poll_response_contact_delete.xml"));
  }

  @Test
  void testSuccess_hostDelete() throws Exception {
    // Host delete poll messages do not have any response data, so ensure that no
    // response data block is produced in the poll message.
    HistoryEntry historyEntry = persistResource(new HistoryEntry.Builder()
        .setClientId("NewRegistrar")
        .setModificationTime(clock.nowUtc().minusDays(1))
        .setType(HistoryEntry.Type.HOST_DELETE)
        .setParent(host)
        .build());
    persistResource(
        new PollMessage.OneTime.Builder()
            .setClientId("NewRegistrar")
            .setMsg("Deleted host ns1.test.example")
            .setParent(historyEntry)
            .setEventTime(clock.nowUtc().minusDays(1))
            .build());
    assertTransactionalFlow(false);
    runFlowAssertResponse(loadFile("poll_response_host_delete.xml"));
  }

  @Test
  void testFailure_messageIdProvided() throws Exception {
    setEppInput("poll_with_id.xml");
    assertTransactionalFlow(false);
    EppException thrown = assertThrows(UnexpectedMessageIdException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }
}
