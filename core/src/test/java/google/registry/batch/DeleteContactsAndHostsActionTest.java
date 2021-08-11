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

package google.registry.batch;

import static com.google.appengine.api.taskqueue.QueueFactory.getQueue;
import static com.google.common.collect.MoreCollectors.onlyElement;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth8.assertThat;
import static google.registry.batch.AsyncTaskEnqueuer.QUEUE_ASYNC_DELETE;
import static google.registry.batch.AsyncTaskMetrics.OperationResult.STALE;
import static google.registry.model.EppResourceUtils.loadByForeignKey;
import static google.registry.model.eppcommon.StatusValue.PENDING_DELETE;
import static google.registry.model.ofy.ObjectifyService.auditedOfy;
import static google.registry.model.reporting.HistoryEntry.Type.CONTACT_DELETE;
import static google.registry.model.reporting.HistoryEntry.Type.CONTACT_DELETE_FAILURE;
import static google.registry.model.reporting.HistoryEntry.Type.CONTACT_TRANSFER_REQUEST;
import static google.registry.model.reporting.HistoryEntry.Type.HOST_DELETE;
import static google.registry.model.reporting.HistoryEntry.Type.HOST_DELETE_FAILURE;
import static google.registry.testing.ContactResourceSubject.assertAboutContacts;
import static google.registry.testing.DatabaseHelper.assertNoBillingEvents;
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.getOnlyHistoryEntryOfType;
import static google.registry.testing.DatabaseHelper.getOnlyPollMessageForHistoryEntry;
import static google.registry.testing.DatabaseHelper.getPollMessages;
import static google.registry.testing.DatabaseHelper.newContactResource;
import static google.registry.testing.DatabaseHelper.newDomainBase;
import static google.registry.testing.DatabaseHelper.newHostResource;
import static google.registry.testing.DatabaseHelper.persistActiveContact;
import static google.registry.testing.DatabaseHelper.persistActiveHost;
import static google.registry.testing.DatabaseHelper.persistContactWithPendingTransfer;
import static google.registry.testing.DatabaseHelper.persistDeletedContact;
import static google.registry.testing.DatabaseHelper.persistDeletedHost;
import static google.registry.testing.DatabaseHelper.persistResource;
import static google.registry.testing.HostResourceSubject.assertAboutHosts;
import static google.registry.testing.TaskQueueHelper.assertDnsTasksEnqueued;
import static google.registry.testing.TaskQueueHelper.assertNoTasksEnqueued;
import static google.registry.testing.TaskQueueHelper.assertTasksEnqueued;
import static google.registry.util.DateTimeUtils.END_OF_TIME;
import static org.joda.time.DateTimeZone.UTC;
import static org.joda.time.Duration.millis;
import static org.joda.time.Duration.standardDays;
import static org.joda.time.Duration.standardHours;
import static org.joda.time.Duration.standardSeconds;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.google.appengine.api.taskqueue.TaskOptions;
import com.google.appengine.api.taskqueue.TaskOptions.Method;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.googlecode.objectify.Key;
import google.registry.batch.AsyncTaskMetrics.OperationResult;
import google.registry.batch.AsyncTaskMetrics.OperationType;
import google.registry.batch.DeleteContactsAndHostsAction.DeleteEppResourceReducer;
import google.registry.model.EppResource;
import google.registry.model.contact.ContactAddress;
import google.registry.model.contact.ContactPhoneNumber;
import google.registry.model.contact.ContactResource;
import google.registry.model.contact.PostalInfo;
import google.registry.model.domain.DomainBase;
import google.registry.model.eppcommon.StatusValue;
import google.registry.model.eppcommon.Trid;
import google.registry.model.eppoutput.EppResponse.ResponseData;
import google.registry.model.host.HostResource;
import google.registry.model.ofy.Ofy;
import google.registry.model.poll.PendingActionNotificationResponse;
import google.registry.model.poll.PendingActionNotificationResponse.ContactPendingActionNotificationResponse;
import google.registry.model.poll.PendingActionNotificationResponse.HostPendingActionNotificationResponse;
import google.registry.model.poll.PollMessage;
import google.registry.model.poll.PollMessage.OneTime;
import google.registry.model.reporting.HistoryEntry;
import google.registry.model.server.Lock;
import google.registry.model.tld.Registry;
import google.registry.model.transfer.ContactTransferData;
import google.registry.model.transfer.TransferData;
import google.registry.model.transfer.TransferResponse;
import google.registry.model.transfer.TransferStatus;
import google.registry.testing.FakeClock;
import google.registry.testing.FakeResponse;
import google.registry.testing.FakeSleeper;
import google.registry.testing.InjectExtension;
import google.registry.testing.TaskQueueHelper.TaskMatcher;
import google.registry.testing.mapreduce.MapreduceTestCase;
import google.registry.util.AppEngineServiceUtils;
import google.registry.util.RequestStatusChecker;
import google.registry.util.Retrier;
import google.registry.util.Sleeper;
import google.registry.util.SystemSleeper;
import java.util.Optional;
import org.joda.time.DateTime;
import org.joda.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockito.Mock;

/** Unit tests for {@link DeleteContactsAndHostsAction}. */
public class DeleteContactsAndHostsActionTest
    extends MapreduceTestCase<DeleteContactsAndHostsAction> {

  @RegisterExtension public final InjectExtension inject = new InjectExtension();

  private AsyncTaskEnqueuer enqueuer;
  private final FakeClock clock = new FakeClock(DateTime.parse("2015-01-15T11:22:33Z"));
  private final FakeResponse fakeResponse = new FakeResponse();
  @Mock private RequestStatusChecker requestStatusChecker;

  private void runMapreduce() throws Exception {
    clock.advanceBy(standardSeconds(5));
    // Apologies for the hard sleeps.  Without them, the tests can be flaky because the tasks aren't
    // quite fully enqueued by the time the tests attempt to lease from the queue.
    Sleeper sleeper = new SystemSleeper();
    sleeper.sleep(millis(50));
    action.run();
    sleeper.sleep(millis(50));
    executeTasksUntilEmpty("mapreduce", clock);
    sleeper.sleep(millis(50));
    clock.advanceBy(standardSeconds(5));
    auditedOfy().clearSessionCache();
  }

  /** Kicks off, but does not run, the mapreduce tasks. Useful for testing validation/setup. */
  private void enqueueMapreduceOnly() {
    clock.advanceBy(standardSeconds(5));
    action.run();
    clock.advanceBy(standardSeconds(5));
    auditedOfy().clearSessionCache();
  }

  @BeforeEach
  void beforeEach() {
    inject.setStaticField(Ofy.class, "clock", clock);
    enqueuer =
        AsyncTaskEnqueuerTest.createForTesting(
            mock(AppEngineServiceUtils.class), clock, Duration.ZERO);
    AsyncTaskMetrics asyncTaskMetricsMock = mock(AsyncTaskMetrics.class);
    action = new DeleteContactsAndHostsAction();
    action.asyncTaskMetrics = asyncTaskMetricsMock;
    inject.setStaticField(DeleteEppResourceReducer.class, "asyncTaskMetrics", asyncTaskMetricsMock);
    action.clock = clock;
    action.mrRunner = makeDefaultRunner();
    action.requestStatusChecker = requestStatusChecker;
    action.response = fakeResponse;
    action.retrier = new Retrier(new FakeSleeper(clock), 1);
    action.queue = getQueue(QUEUE_ASYNC_DELETE);
    when(requestStatusChecker.getLogId()).thenReturn("requestId");
    when(requestStatusChecker.isRunning(anyString()))
        .thenThrow(new AssertionError("Should not be called"));

    createTld("tld");
    clock.advanceOneMilli();
  }

  @Test
  void testSuccess_contact_referencedByActiveDomain_doesNotGetDeleted() throws Exception {
    ContactResource contact = persistContactPendingDelete("blah8221");
    persistResource(newDomainBase("example.tld", contact));
    DateTime timeEnqueued = clock.nowUtc();
    enqueuer.enqueueAsyncDelete(
        contact,
        timeEnqueued,
        "TheRegistrar",
        Trid.create("fakeClientTrid", "fakeServerTrid"),
        false);
    runMapreduce();
    ContactResource contactUpdated =
        loadByForeignKey(ContactResource.class, "blah8221", clock.nowUtc()).get();
    assertAboutContacts()
        .that(contactUpdated)
        .doesNotHaveStatusValue(PENDING_DELETE)
        .and()
        .hasDeletionTime(END_OF_TIME);
    DomainBase domainReloaded =
        loadByForeignKey(DomainBase.class, "example.tld", clock.nowUtc()).get();
    // We have to check for the objectify key here, specifically, as the SQL side of the key does
    // not get persisted.
    assertThat(domainReloaded.getReferencedContacts()).contains(contactUpdated.createVKey());
    HistoryEntry historyEntry =
        getOnlyHistoryEntryOfType(contactUpdated, HistoryEntry.Type.CONTACT_DELETE_FAILURE);
    assertPollMessageFor(
        historyEntry,
        "TheRegistrar",
        "Can't delete contact blah8221 because it is referenced by a domain.",
        false,
        contact,
        Optional.of("fakeClientTrid"));
    assertNoTasksEnqueued(QUEUE_ASYNC_DELETE);
    verify(action.asyncTaskMetrics).recordContactHostDeletionBatchSize(1L);
    verify(action.asyncTaskMetrics)
        .recordAsyncFlowResult(OperationType.CONTACT_DELETE, OperationResult.FAILURE, timeEnqueued);
    verifyNoMoreInteractions(action.asyncTaskMetrics);
  }

  @Test
  void testSuccess_contact_notReferenced_getsDeleted_andPiiWipedOut() throws Exception {
    runSuccessfulContactDeletionTest(Optional.of("fakeClientTrid"));
  }

  @Test
  void testSuccess_contact_andNoClientTrid_deletesSuccessfully() throws Exception {
    runSuccessfulContactDeletionTest(Optional.empty());
  }

  @Test
  void test_cannotAcquireLock() {
    // Make lock acquisition fail.
    acquireLock();
    enqueueMapreduceOnly();
    assertThat(fakeResponse.getPayload()).isEqualTo("Can't acquire lock; aborting.");
  }

  @Test
  void test_mapreduceHasWorkToDo_lockIsAcquired() {
    ContactResource contact = persistContactPendingDelete("blah8221");
    persistResource(newDomainBase("example.tld", contact));
    DateTime timeEnqueued = clock.nowUtc();
    enqueuer.enqueueAsyncDelete(
        contact,
        timeEnqueued,
        "TheRegistrar",
        Trid.create("fakeClientTrid", "fakeServerTrid"),
        false);
    enqueueMapreduceOnly();
    assertThat(acquireLock()).isEmpty();
  }

  @Test
  void test_noTasksToLease_releasesLockImmediately() {
    enqueueMapreduceOnly();
    // If the Lock was correctly released, then we can acquire it now.
    assertThat(acquireLock()).isPresent();
  }

  private void runSuccessfulContactDeletionTest(Optional<String> clientTrid) throws Exception {
    ContactResource contact = persistContactWithPii("jim919");
    DateTime timeEnqueued = clock.nowUtc();
    enqueuer.enqueueAsyncDelete(
        contact,
        timeEnqueued,
        "TheRegistrar",
        Trid.create(clientTrid.orElse(null), "fakeServerTrid"),
        false);
    runMapreduce();
    assertThat(loadByForeignKey(ContactResource.class, "jim919", clock.nowUtc())).isEmpty();
    ContactResource contactAfterDeletion = auditedOfy().load().entity(contact).now();
    assertAboutContacts()
        .that(contactAfterDeletion)
        .isNotActiveAt(clock.nowUtc())
        // Note that there will be another history entry of CONTACT_PENDING_DELETE, but this is
        // added by the flow and not the mapreduce itself.
        .and()
        .hasOnlyOneHistoryEntryWhich()
        .hasType(CONTACT_DELETE);
    assertAboutContacts()
        .that(contactAfterDeletion)
        .hasNullLocalizedPostalInfo()
        .and()
        .hasNullInternationalizedPostalInfo()
        .and()
        .hasNullEmailAddress()
        .and()
        .hasNullVoiceNumber()
        .and()
        .hasNullFaxNumber();
    HistoryEntry historyEntry = getOnlyHistoryEntryOfType(contactAfterDeletion, CONTACT_DELETE);
    assertPollMessageFor(
        historyEntry, "TheRegistrar", "Deleted contact jim919.", true, contact, clientTrid);
    assertNoTasksEnqueued(QUEUE_ASYNC_DELETE);
    verify(action.asyncTaskMetrics).recordContactHostDeletionBatchSize(1L);
    verify(action.asyncTaskMetrics)
        .recordAsyncFlowResult(OperationType.CONTACT_DELETE, OperationResult.SUCCESS, timeEnqueued);
    verifyNoMoreInteractions(action.asyncTaskMetrics);

  }

  @Test
  void testSuccess_contactWithoutPendingTransfer_isDeletedAndHasNoTransferData() throws Exception {
    ContactResource contact = persistContactPendingDelete("blah8221");
    enqueuer.enqueueAsyncDelete(
        contact,
        clock.nowUtc(),
        "TheRegistrar",
        Trid.create("fakeClientTrid", "fakeServerTrid"),
        false);
    runMapreduce();
    ContactResource contactAfterDeletion = auditedOfy().load().entity(contact).now();
    assertThat(contactAfterDeletion.getTransferData()).isEqualTo(ContactTransferData.EMPTY);
  }

  @Test
  void testSuccess_contactWithPendingTransfer_getsDeleted() throws Exception {
    DateTime transferRequestTime = clock.nowUtc().minusDays(3);
    ContactResource contact =
        persistContactWithPendingTransfer(
            newContactResource("sh8013").asBuilder().addStatusValue(PENDING_DELETE).build(),
            transferRequestTime,
            transferRequestTime.plus(Registry.DEFAULT_TRANSFER_GRACE_PERIOD),
            clock.nowUtc());
    enqueuer.enqueueAsyncDelete(
        contact,
        clock.nowUtc(),
        "TheRegistrar",
        Trid.create("fakeClientTrid", "fakeServerTrid"),
        false);
    runMapreduce();
    // Check that the contact is deleted as of now.
    assertThat(loadByForeignKey(ContactResource.class, "sh8013", clock.nowUtc())).isEmpty();
    // Check that it's still there (it wasn't deleted yesterday) and that it has history.
    ContactResource softDeletedContact =
        loadByForeignKey(ContactResource.class, "sh8013", clock.nowUtc().minusDays(1)).get();
    assertAboutContacts()
        .that(softDeletedContact)
        .hasOneHistoryEntryEachOfTypes(CONTACT_TRANSFER_REQUEST, CONTACT_DELETE);
    // Check that the transfer data reflects the cancelled transfer as we expect.
    TransferData oldTransferData = contact.getTransferData();
    assertThat(softDeletedContact.getTransferData())
        .isEqualTo(
            oldTransferData.copyConstantFieldsToBuilder()
                .setTransferStatus(TransferStatus.SERVER_CANCELLED)
                .setPendingTransferExpirationTime(softDeletedContact.getDeletionTime())
                .build());
    assertNoBillingEvents();
    PollMessage deletePollMessage =
        Iterables.getOnlyElement(getPollMessages("TheRegistrar", clock.nowUtc().plusMonths(1)));
    assertThat(deletePollMessage.getMsg()).isEqualTo("Deleted contact sh8013.");
    // The poll message in the future to the gaining registrar should be gone too, but there
    // should be one at the current time to the gaining registrar.
    PollMessage gainingPollMessage =
        Iterables.getOnlyElement(getPollMessages("NewRegistrar", clock.nowUtc()));
    assertThat(gainingPollMessage.getEventTime()).isLessThan(clock.nowUtc());
    assertThat(
            gainingPollMessage
                .getResponseData()
                .stream()
                .filter(TransferResponse.class::isInstance)
                .map(TransferResponse.class::cast)
                .collect(onlyElement())
                .getTransferStatus())
        .isEqualTo(TransferStatus.SERVER_CANCELLED);
    PendingActionNotificationResponse panData =
        gainingPollMessage
            .getResponseData()
            .stream()
            .filter(PendingActionNotificationResponse.class::isInstance)
            .map(PendingActionNotificationResponse.class::cast)
            .collect(onlyElement());
    assertThat(panData.getTrid())
        .isEqualTo(Trid.create("transferClient-trid", "transferServer-trid"));
    assertThat(panData.getActionResult()).isFalse();
    assertNoTasksEnqueued(QUEUE_ASYNC_DELETE);
  }

  @Test
  void testSuccess_contact_referencedByDeletedDomain_getsDeleted() throws Exception {
    ContactResource contactUsed = persistContactPendingDelete("blah1234");
    persistResource(
        newDomainBase("example.tld", contactUsed)
            .asBuilder()
            .setDeletionTime(clock.nowUtc().minusDays(3))
            .build());
    enqueuer.enqueueAsyncDelete(
        contactUsed,
        clock.nowUtc(),
        "TheRegistrar",
        Trid.create("fakeClientTrid", "fakeServerTrid"),
        false);
    runMapreduce();
    assertThat(loadByForeignKey(ContactResource.class, "blah1234", clock.nowUtc())).isEmpty();
    ContactResource contactBeforeDeletion =
        loadByForeignKey(ContactResource.class, "blah1234", clock.nowUtc().minusDays(1)).get();
    assertAboutContacts()
        .that(contactBeforeDeletion)
        .isNotActiveAt(clock.nowUtc())
        .and()
        .hasExactlyStatusValues(StatusValue.OK)
        // Note that there will be another history entry of CONTACT_PENDING_DELETE, but this is
        // added by the flow and not the mapreduce itself.
        .and()
        .hasOnlyOneHistoryEntryWhich()
        .hasType(CONTACT_DELETE);
    HistoryEntry historyEntry = getOnlyHistoryEntryOfType(contactBeforeDeletion, CONTACT_DELETE);
    assertPollMessageFor(
        historyEntry,
        "TheRegistrar",
        "Deleted contact blah1234.",
        true,
        contactUsed,
        Optional.of("fakeClientTrid"));
    assertNoTasksEnqueued(QUEUE_ASYNC_DELETE);
  }

  @Test
  void testSuccess_contact_notRequestedByOwner_doesNotGetDeleted() throws Exception {
    ContactResource contact = persistContactPendingDelete("jane0991");
    enqueuer.enqueueAsyncDelete(
        contact,
        clock.nowUtc(),
        "OtherRegistrar",
        Trid.create("fakeClientTrid", "fakeServerTrid"),
        false);
    runMapreduce();
    ContactResource contactAfter =
        loadByForeignKey(ContactResource.class, "jane0991", clock.nowUtc()).get();
    assertAboutContacts()
        .that(contactAfter)
        .doesNotHaveStatusValue(PENDING_DELETE)
        .and()
        .hasDeletionTime(END_OF_TIME);
    HistoryEntry historyEntry = getOnlyHistoryEntryOfType(contactAfter, CONTACT_DELETE_FAILURE);
    assertPollMessageFor(
        historyEntry,
        "OtherRegistrar",
        "Can't delete contact jane0991 because it was transferred prior to deletion.",
        false,
        contact,
        Optional.of("fakeClientTrid"));
    assertNoTasksEnqueued(QUEUE_ASYNC_DELETE);
  }

  @Test
  void testSuccess_contact_notRequestedByOwner_isSuperuser_getsDeleted() throws Exception {
    ContactResource contact = persistContactWithPii("nate007");
    enqueuer.enqueueAsyncDelete(
        contact,
        clock.nowUtc(),
        "OtherRegistrar",
        Trid.create("fakeClientTrid", "fakeServerTrid"),
        true);
    runMapreduce();
    assertThat(loadByForeignKey(ContactResource.class, "nate007", clock.nowUtc())).isEmpty();
    ContactResource contactAfterDeletion = auditedOfy().load().entity(contact).now();
    assertAboutContacts()
        .that(contactAfterDeletion)
        .isNotActiveAt(clock.nowUtc())
        // Note that there will be another history entry of CONTACT_PENDING_DELETE, but this is
        // added by the flow and not the mapreduce itself.
        .and()
        .hasOnlyOneHistoryEntryWhich()
        .hasType(CONTACT_DELETE);
    assertAboutContacts()
        .that(contactAfterDeletion)
        .hasNullLocalizedPostalInfo()
        .and()
        .hasNullInternationalizedPostalInfo()
        .and()
        .hasNullEmailAddress()
        .and()
        .hasNullVoiceNumber()
        .and()
        .hasNullFaxNumber();
    HistoryEntry historyEntry = getOnlyHistoryEntryOfType(contactAfterDeletion, CONTACT_DELETE);
    assertPollMessageFor(
        historyEntry,
        "OtherRegistrar",
        "Deleted contact nate007.",
        true,
        contact,
        Optional.of("fakeClientTrid"));
    assertNoTasksEnqueued(QUEUE_ASYNC_DELETE);
  }

  @Test
  void testSuccess_targetResourcesDontExist_areDelayedForADay() {
    ContactResource contactNotSaved = newContactResource("somecontact");
    HostResource hostNotSaved = newHostResource("a11.blah.foo");
    DateTime timeBeforeRun = clock.nowUtc();
    enqueuer.enqueueAsyncDelete(
        contactNotSaved,
        timeBeforeRun,
        "TheRegistrar",
        Trid.create("fakeClientTrid", "fakeServerTrid"),
        false);
    enqueuer.enqueueAsyncDelete(
        hostNotSaved,
        timeBeforeRun,
        "TheRegistrar",
        Trid.create("fakeClientTrid", "fakeServerTrid"),
        false);
    enqueueMapreduceOnly();
    assertTasksEnqueued(
        QUEUE_ASYNC_DELETE,
        new TaskMatcher()
            .etaDelta(standardHours(23), standardHours(25))
            .param("resourceKey", Key.create(contactNotSaved).getString())
            .param("requestingClientId", "TheRegistrar")
            .param("clientTransactionId", "fakeClientTrid")
            .param("serverTransactionId", "fakeServerTrid")
            .param("isSuperuser", "false")
            .param("requestedTime", timeBeforeRun.toString()),
        new TaskMatcher()
            .etaDelta(standardHours(23), standardHours(25))
            .param("resourceKey", Key.create(hostNotSaved).getString())
            .param("requestingClientId", "TheRegistrar")
            .param("clientTransactionId", "fakeClientTrid")
            .param("serverTransactionId", "fakeServerTrid")
            .param("isSuperuser", "false")
            .param("requestedTime", timeBeforeRun.toString()));
    assertThat(acquireLock()).isPresent();
  }

  @Test
  void testSuccess_unparseableTasks_areDelayedForADay() {
    TaskOptions task =
        TaskOptions.Builder.withMethod(Method.PULL).param("gobbledygook", "kljhadfgsd9f7gsdfh");
    getQueue(QUEUE_ASYNC_DELETE).add(task);
    enqueueMapreduceOnly();
    assertTasksEnqueued(
        QUEUE_ASYNC_DELETE,
        new TaskMatcher()
            .payload("gobbledygook=kljhadfgsd9f7gsdfh")
            .etaDelta(standardHours(23), standardHours(25)));
    verify(action.asyncTaskMetrics).recordContactHostDeletionBatchSize(1L);
    verifyNoMoreInteractions(action.asyncTaskMetrics);
    assertThat(acquireLock()).isPresent();
  }

  @Test
  void testSuccess_resourcesNotInPendingDelete_areSkipped() {
    ContactResource contact = persistActiveContact("blah2222");
    HostResource host = persistActiveHost("rustles.your.jimmies");
    DateTime timeEnqueued = clock.nowUtc();
    enqueuer.enqueueAsyncDelete(
        contact,
        timeEnqueued,
        "TheRegistrar",
        Trid.create("fakeClientTrid", "fakeServerTrid"),
        false);
    enqueuer.enqueueAsyncDelete(
        host,
        timeEnqueued,
        "TheRegistrar",
        Trid.create("fakeClientTrid", "fakeServerTrid"),
        false);
    enqueueMapreduceOnly();
    assertThat(loadByForeignKey(ContactResource.class, "blah2222", clock.nowUtc()))
        .hasValue(contact);
    assertThat(loadByForeignKey(HostResource.class, "rustles.your.jimmies", clock.nowUtc()))
        .hasValue(host);
    assertNoTasksEnqueued(QUEUE_ASYNC_DELETE);
    verify(action.asyncTaskMetrics).recordContactHostDeletionBatchSize(2L);
    verify(action.asyncTaskMetrics)
        .recordAsyncFlowResult(OperationType.CONTACT_DELETE, STALE, timeEnqueued);
    verify(action.asyncTaskMetrics)
        .recordAsyncFlowResult(OperationType.HOST_DELETE, STALE, timeEnqueued);
    verifyNoMoreInteractions(action.asyncTaskMetrics);
    assertThat(acquireLock()).isPresent();
  }

  @Test
  void testSuccess_alreadyDeletedResources_areSkipped() {
    ContactResource contactDeleted = persistDeletedContact("blah1236", clock.nowUtc().minusDays(2));
    HostResource hostDeleted = persistDeletedHost("a.lim.lop", clock.nowUtc().minusDays(3));
    enqueuer.enqueueAsyncDelete(
        contactDeleted,
        clock.nowUtc(),
        "TheRegistrar",
        Trid.create("fakeClientTrid", "fakeServerTrid"),
        false);
    enqueuer.enqueueAsyncDelete(
        hostDeleted,
        clock.nowUtc(),
        "TheRegistrar",
        Trid.create("fakeClientTrid", "fakeServerTrid"),
        false);
    enqueueMapreduceOnly();
    assertThat(auditedOfy().load().entity(contactDeleted).now()).isEqualTo(contactDeleted);
    assertThat(auditedOfy().load().entity(hostDeleted).now()).isEqualTo(hostDeleted);
    assertNoTasksEnqueued(QUEUE_ASYNC_DELETE);
    assertThat(acquireLock()).isPresent();
  }

  @Test
  void testSuccess_host_referencedByActiveDomain_doesNotGetDeleted() throws Exception {
    HostResource host = persistHostPendingDelete("ns1.example.tld");
    persistUsedDomain("example.tld", persistActiveContact("abc456"), host);
    DateTime timeEnqueued = clock.nowUtc();
    enqueuer.enqueueAsyncDelete(
        host,
        timeEnqueued,
        "TheRegistrar",
        Trid.create("fakeClientTrid", "fakeServerTrid"),
        false);
    runMapreduce();
    HostResource hostAfter =
        loadByForeignKey(HostResource.class, "ns1.example.tld", clock.nowUtc()).get();
    assertAboutHosts()
        .that(hostAfter)
        .doesNotHaveStatusValue(PENDING_DELETE)
        .and()
        .hasDeletionTime(END_OF_TIME);
    DomainBase domain =
        loadByForeignKey(DomainBase.class, "example.tld", clock.nowUtc()).get();
    assertThat(domain.getNameservers()).contains(hostAfter.createVKey());
    HistoryEntry historyEntry = getOnlyHistoryEntryOfType(hostAfter, HOST_DELETE_FAILURE);
    assertPollMessageFor(
        historyEntry,
        "TheRegistrar",
        "Can't delete host ns1.example.tld because it is referenced by a domain.",
        false,
        host,
        Optional.of("fakeClientTrid"));
    assertNoTasksEnqueued(QUEUE_ASYNC_DELETE);
    verify(action.asyncTaskMetrics).recordContactHostDeletionBatchSize(1L);
    verify(action.asyncTaskMetrics)
        .recordAsyncFlowResult(OperationType.HOST_DELETE, OperationResult.FAILURE, timeEnqueued);
    verifyNoMoreInteractions(action.asyncTaskMetrics);
  }

  @Test
  void testSuccess_host_notReferenced_getsDeleted() throws Exception {
    runSuccessfulHostDeletionTest(Optional.of("fakeClientTrid"));
  }

  @Test
  void testSuccess_host_andNoClientTrid_deletesSuccessfully() throws Exception {
    runSuccessfulHostDeletionTest(Optional.empty());
  }

  private void runSuccessfulHostDeletionTest(Optional<String> clientTrid) throws Exception {
    HostResource host = persistHostPendingDelete("ns2.example.tld");
    DateTime timeEnqueued = clock.nowUtc();
    enqueuer.enqueueAsyncDelete(
        host,
        timeEnqueued,
        "TheRegistrar",
        Trid.create(clientTrid.orElse(null), "fakeServerTrid"),
        false);
    runMapreduce();
    assertThat(loadByForeignKey(HostResource.class, "ns2.example.tld", clock.nowUtc())).isEmpty();
    HostResource hostBeforeDeletion =
        loadByForeignKey(HostResource.class, "ns2.example.tld", clock.nowUtc().minusDays(1)).get();
    assertAboutHosts()
        .that(hostBeforeDeletion)
        .isNotActiveAt(clock.nowUtc())
        .and()
        .hasExactlyStatusValues(StatusValue.OK)
        // Note that there will be another history entry of HOST_PENDING_DELETE, but this is
        // added by the flow and not the mapreduce itself.
        .and()
        .hasOnlyOneHistoryEntryWhich()
        .hasType(HOST_DELETE);
    HistoryEntry historyEntry = getOnlyHistoryEntryOfType(hostBeforeDeletion, HOST_DELETE);
    assertPollMessageFor(
        historyEntry,
        "TheRegistrar",
        "Deleted host ns2.example.tld.",
        true,
        host,
        clientTrid);
    assertNoTasksEnqueued(QUEUE_ASYNC_DELETE);
    verify(action.asyncTaskMetrics).recordContactHostDeletionBatchSize(1L);
    verify(action.asyncTaskMetrics)
        .recordAsyncFlowResult(OperationType.HOST_DELETE, OperationResult.SUCCESS, timeEnqueued);
    verifyNoMoreInteractions(action.asyncTaskMetrics);
  }

  @Test
  void testSuccess_host_referencedByDeletedDomain_getsDeleted() throws Exception {
    HostResource host = persistHostPendingDelete("ns1.example.tld");
    persistResource(
        newDomainBase("example.tld")
            .asBuilder()
            .setNameservers(ImmutableSet.of(host.createVKey()))
            .setDeletionTime(clock.nowUtc().minusDays(5))
            .build());
    enqueuer.enqueueAsyncDelete(
        host,
        clock.nowUtc(),
        "TheRegistrar",
        Trid.create("fakeClientTrid", "fakeServerTrid"),
        false);
    runMapreduce();
    assertThat(loadByForeignKey(HostResource.class, "ns1.example.tld", clock.nowUtc())).isEmpty();
    HostResource hostBeforeDeletion =
        loadByForeignKey(HostResource.class, "ns1.example.tld", clock.nowUtc().minusDays(1)).get();
    assertAboutHosts()
        .that(hostBeforeDeletion)
        .isNotActiveAt(clock.nowUtc())
        .and()
        .hasExactlyStatusValues(StatusValue.OK)
        // Note that there will be another history entry of HOST_PENDING_DELETE, but this is
        // added by the flow and not the mapreduce itself.
        .and()
        .hasOnlyOneHistoryEntryWhich()
        .hasType(HOST_DELETE);
    HistoryEntry historyEntry = getOnlyHistoryEntryOfType(hostBeforeDeletion, HOST_DELETE);
    assertPollMessageFor(
        historyEntry,
        "TheRegistrar",
        "Deleted host ns1.example.tld.",
        true,
        host,
        Optional.of("fakeClientTrid"));
    assertNoTasksEnqueued(QUEUE_ASYNC_DELETE);
  }

  @Test
  void testSuccess_subordinateHost_getsDeleted() throws Exception {
    DomainBase domain =
        persistResource(
            newDomainBase("example.tld")
                .asBuilder()
                .setSubordinateHosts(ImmutableSet.of("ns2.example.tld"))
                .build());
    HostResource host =
        persistResource(
            persistHostPendingDelete("ns2.example.tld")
                .asBuilder()
                .setSuperordinateDomain(domain.createVKey())
                .build());
    enqueuer.enqueueAsyncDelete(
        host,
        clock.nowUtc(),
        "TheRegistrar",
        Trid.create("fakeClientTrid", "fakeServerTrid"),
        false);
    runMapreduce();
    // Check that the host is deleted as of now.
    assertThat(loadByForeignKey(HostResource.class, "ns2.example.tld", clock.nowUtc())).isEmpty();
    assertNoBillingEvents();
    assertThat(
            loadByForeignKey(DomainBase.class, "example.tld", clock.nowUtc())
                .get()
                .getSubordinateHosts())
        .isEmpty();
    assertDnsTasksEnqueued("ns2.example.tld");
    HostResource hostBeforeDeletion =
        loadByForeignKey(HostResource.class, "ns2.example.tld", clock.nowUtc().minusDays(1)).get();
    assertAboutHosts()
        .that(hostBeforeDeletion)
        .isNotActiveAt(clock.nowUtc())
        .and()
        .hasExactlyStatusValues(StatusValue.OK)
        .and()
        .hasOnlyOneHistoryEntryWhich()
        .hasType(HOST_DELETE);
    HistoryEntry historyEntry = getOnlyHistoryEntryOfType(hostBeforeDeletion, HOST_DELETE);
    assertPollMessageFor(
        historyEntry,
        "TheRegistrar",
        "Deleted host ns2.example.tld.",
        true,
        host,
        Optional.of("fakeClientTrid"));
    assertNoTasksEnqueued(QUEUE_ASYNC_DELETE);
  }

  @Test
  void testSuccess_host_notRequestedByOwner_doesNotGetDeleted() throws Exception {
    HostResource host = persistHostPendingDelete("ns2.example.tld");
    enqueuer.enqueueAsyncDelete(
        host,
        clock.nowUtc(),
        "OtherRegistrar",
        Trid.create("fakeClientTrid", "fakeServerTrid"),
        false);
    runMapreduce();
    HostResource hostAfter =
        loadByForeignKey(HostResource.class, "ns2.example.tld", clock.nowUtc()).get();
    assertAboutHosts()
        .that(hostAfter)
        .doesNotHaveStatusValue(PENDING_DELETE)
        .and()
        .hasDeletionTime(END_OF_TIME);
    HistoryEntry historyEntry = getOnlyHistoryEntryOfType(host, HOST_DELETE_FAILURE);
    assertPollMessageFor(
        historyEntry,
        "OtherRegistrar",
        "Can't delete host ns2.example.tld because it was transferred prior to deletion.",
        false,
        host,
        Optional.of("fakeClientTrid"));
    assertNoTasksEnqueued(QUEUE_ASYNC_DELETE);
  }

  @Test
  void testSuccess_host_notRequestedByOwner_isSuperuser_getsDeleted() throws Exception {
    HostResource host = persistHostPendingDelete("ns66.example.tld");
    enqueuer.enqueueAsyncDelete(
        host,
        clock.nowUtc(),
        "OtherRegistrar",
        Trid.create("fakeClientTrid", "fakeServerTrid"),
        true);
    runMapreduce();
    assertThat(loadByForeignKey(HostResource.class, "ns66.example.tld", clock.nowUtc())).isEmpty();
    HostResource hostBeforeDeletion =
        loadByForeignKey(HostResource.class, "ns66.example.tld", clock.nowUtc().minusDays(1)).get();
    assertAboutHosts()
        .that(hostBeforeDeletion)
        .isNotActiveAt(clock.nowUtc())
        .and()
        .hasExactlyStatusValues(StatusValue.OK)
        // Note that there will be another history entry of HOST_PENDING_DELETE, but this is
        // added by the flow and not the mapreduce itself.
        .and()
        .hasOnlyOneHistoryEntryWhich()
        .hasType(HOST_DELETE);
    HistoryEntry historyEntry = getOnlyHistoryEntryOfType(hostBeforeDeletion, HOST_DELETE);
    assertPollMessageFor(
        historyEntry,
        "OtherRegistrar",
        "Deleted host ns66.example.tld.",
        true,
        host,
        Optional.of("fakeClientTrid"));
    assertNoTasksEnqueued(QUEUE_ASYNC_DELETE);
  }

  @Test
  void testSuccess_deleteABunchOfContactsAndHosts_butNotSome() throws Exception {
    ContactResource c1 = persistContactPendingDelete("nsaid54");
    ContactResource c2 = persistContactPendingDelete("nsaid55");
    ContactResource c3 = persistContactPendingDelete("nsaid57");
    HostResource h1 = persistHostPendingDelete("nn5.example.tld");
    HostResource h2 = persistHostPendingDelete("no.foos.ball");
    HostResource h3 = persistHostPendingDelete("slime.wars.fun");
    ContactResource c4 = persistContactPendingDelete("iaminuse6");
    HostResource h4 = persistHostPendingDelete("used.host.com");
    persistUsedDomain("usescontactandhost.tld", c4, h4);
    for (EppResource resource : ImmutableList.<EppResource>of(c1, c2, c3, c4, h1, h2, h3, h4)) {
      enqueuer.enqueueAsyncDelete(
          resource,
          clock.nowUtc(),
          "TheRegistrar",
          Trid.create("fakeClientTrid", "fakeServerTrid"),
          false);
    }
    runMapreduce();
    for (EppResource resource : ImmutableList.<EppResource>of(c1, c2, c3, h1, h2, h3)) {
      EppResource loaded = auditedOfy().load().entity(resource).now();
      assertThat(loaded.getDeletionTime()).isLessThan(DateTime.now(UTC));
      assertThat(loaded.getStatusValues()).doesNotContain(PENDING_DELETE);
    }
    for (EppResource resource : ImmutableList.<EppResource>of(c4, h4)) {
      EppResource loaded = auditedOfy().load().entity(resource).now();
      assertThat(loaded.getDeletionTime()).isEqualTo(END_OF_TIME);
      assertThat(loaded.getStatusValues()).doesNotContain(PENDING_DELETE);
    }
    assertNoTasksEnqueued(QUEUE_ASYNC_DELETE);
  }

  private static ContactResource persistContactWithPii(String contactId) {
    return persistResource(
        newContactResource(contactId)
            .asBuilder()
            .setLocalizedPostalInfo(
                new PostalInfo.Builder()
                    .setType(PostalInfo.Type.LOCALIZED)
                    .setAddress(
                        new ContactAddress.Builder()
                            .setStreet(ImmutableList.of("123 Grand Ave"))
                            .build())
                    .build())
            .setInternationalizedPostalInfo(
                new PostalInfo.Builder()
                    .setType(PostalInfo.Type.INTERNATIONALIZED)
                    .setAddress(
                        new ContactAddress.Builder()
                            .setStreet(ImmutableList.of("123 Avenida Grande"))
                            .build())
                    .build())
            .setEmailAddress("bob@bob.com")
            .setVoiceNumber(new ContactPhoneNumber.Builder().setPhoneNumber("555-1212").build())
            .setFaxNumber(new ContactPhoneNumber.Builder().setPhoneNumber("555-1212").build())
            .addStatusValue(PENDING_DELETE)
            .build());
  }

  /**
   * Helper method to check that one poll message exists with a given history entry, resource,
   * client id, and message. Also checks that the only resulting async response matches the resource
   * type, and has the appropriate actionResult, nameOrId, and Trid.
   */
  private static void assertPollMessageFor(
      HistoryEntry historyEntry,
      String clientId,
      String msg,
      boolean expectedActionResult,
      EppResource resource,
      Optional<String> clientTrid) {
    PollMessage.OneTime pollMessage = (OneTime) getOnlyPollMessageForHistoryEntry(historyEntry);
    assertThat(pollMessage.getMsg()).isEqualTo(msg);
    assertThat(pollMessage.getClientId()).isEqualTo(clientId);

    ImmutableList<ResponseData> pollResponses = pollMessage.getResponseData();
    assertThat(pollResponses).hasSize(1);
    ResponseData responseData = pollMessage.getResponseData().get(0);

    String expectedResourceName;
    if (resource instanceof HostResource) {
      assertThat(responseData).isInstanceOf(HostPendingActionNotificationResponse.class);
      expectedResourceName = ((HostResource) resource).getHostName();
    } else {
      assertThat(responseData).isInstanceOf(ContactPendingActionNotificationResponse.class);
      expectedResourceName = ((ContactResource) resource).getContactId();
    }
    PendingActionNotificationResponse pendingResponse =
        (PendingActionNotificationResponse) responseData;
    assertThat(pendingResponse.getActionResult()).isEqualTo(expectedActionResult);
    assertThat(pendingResponse.getNameAsString()).isEqualTo(expectedResourceName);
    Trid trid = pendingResponse.getTrid();
    assertThat(trid.getClientTransactionId()).isEqualTo(clientTrid);
    assertThat(trid.getServerTransactionId()).isEqualTo("fakeServerTrid");
  }

  private static ContactResource persistContactPendingDelete(String contactId) {
    return persistResource(
        newContactResource(contactId).asBuilder().addStatusValue(PENDING_DELETE).build());
  }

  private static HostResource persistHostPendingDelete(String hostName) {
    return persistResource(
        newHostResource(hostName).asBuilder().addStatusValue(PENDING_DELETE).build());
  }

  private static DomainBase persistUsedDomain(
      String domainName, ContactResource contact, HostResource host) {
    return persistResource(
        newDomainBase(domainName, contact)
            .asBuilder()
            .setNameservers(ImmutableSet.of(host.createVKey()))
            .build());
  }

  private Optional<Lock> acquireLock() {
    return Lock.acquire(
        DeleteContactsAndHostsAction.class.getSimpleName(),
        null,
        standardDays(30),
        requestStatusChecker,
        false);
  }
}
