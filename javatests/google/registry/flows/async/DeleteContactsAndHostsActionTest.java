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

package google.registry.flows.async;

import static com.google.appengine.api.taskqueue.QueueFactory.getQueue;
import static com.google.common.collect.Iterables.getOnlyElement;
import static com.google.common.truth.Truth.assertThat;
import static google.registry.flows.async.DeleteContactsAndHostsAction.QUEUE_ASYNC_DELETE;
import static google.registry.model.EppResourceUtils.loadByForeignKey;
import static google.registry.model.eppcommon.StatusValue.PENDING_DELETE;
import static google.registry.model.ofy.ObjectifyService.ofy;
import static google.registry.model.reporting.HistoryEntry.Type.CONTACT_DELETE;
import static google.registry.model.reporting.HistoryEntry.Type.CONTACT_DELETE_FAILURE;
import static google.registry.model.reporting.HistoryEntry.Type.CONTACT_TRANSFER_REQUEST;
import static google.registry.model.reporting.HistoryEntry.Type.HOST_DELETE;
import static google.registry.model.reporting.HistoryEntry.Type.HOST_DELETE_FAILURE;
import static google.registry.model.transfer.TransferStatus.SERVER_CANCELLED;
import static google.registry.testing.ContactResourceSubject.assertAboutContacts;
import static google.registry.testing.DatastoreHelper.assertNoBillingEvents;
import static google.registry.testing.DatastoreHelper.createTld;
import static google.registry.testing.DatastoreHelper.getOnlyHistoryEntryOfType;
import static google.registry.testing.DatastoreHelper.getOnlyPollMessageForHistoryEntry;
import static google.registry.testing.DatastoreHelper.getPollMessages;
import static google.registry.testing.DatastoreHelper.newContactResource;
import static google.registry.testing.DatastoreHelper.newDomainResource;
import static google.registry.testing.DatastoreHelper.newHostResource;
import static google.registry.testing.DatastoreHelper.persistActiveContact;
import static google.registry.testing.DatastoreHelper.persistActiveHost;
import static google.registry.testing.DatastoreHelper.persistContactWithPendingTransfer;
import static google.registry.testing.DatastoreHelper.persistDeletedContact;
import static google.registry.testing.DatastoreHelper.persistDeletedHost;
import static google.registry.testing.DatastoreHelper.persistResource;
import static google.registry.testing.HostResourceSubject.assertAboutHosts;
import static google.registry.testing.TaskQueueHelper.assertDnsTasksEnqueued;
import static google.registry.testing.TaskQueueHelper.assertNoTasksEnqueued;
import static google.registry.testing.TaskQueueHelper.assertTasksEnqueued;
import static google.registry.util.DateTimeUtils.END_OF_TIME;
import static org.joda.time.DateTimeZone.UTC;
import static org.joda.time.Duration.millis;
import static org.joda.time.Duration.standardHours;
import static org.joda.time.Duration.standardSeconds;

import com.google.appengine.api.taskqueue.QueueFactory;
import com.google.appengine.api.taskqueue.TaskOptions;
import com.google.appengine.api.taskqueue.TaskOptions.Method;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.googlecode.objectify.Key;
import google.registry.model.EppResource;
import google.registry.model.contact.ContactAddress;
import google.registry.model.contact.ContactPhoneNumber;
import google.registry.model.contact.ContactResource;
import google.registry.model.contact.PostalInfo;
import google.registry.model.domain.DomainResource;
import google.registry.model.eppcommon.StatusValue;
import google.registry.model.eppcommon.Trid;
import google.registry.model.host.HostResource;
import google.registry.model.ofy.Ofy;
import google.registry.model.poll.PendingActionNotificationResponse;
import google.registry.model.poll.PollMessage;
import google.registry.model.poll.PollMessage.OneTime;
import google.registry.model.registry.Registry;
import google.registry.model.reporting.HistoryEntry;
import google.registry.model.transfer.TransferResponse;
import google.registry.testing.ExceptionRule;
import google.registry.testing.FakeClock;
import google.registry.testing.FakeResponse;
import google.registry.testing.FakeSleeper;
import google.registry.testing.InjectRule;
import google.registry.testing.TaskQueueHelper.TaskMatcher;
import google.registry.testing.mapreduce.MapreduceTestCase;
import google.registry.util.Retrier;
import google.registry.util.Sleeper;
import google.registry.util.SystemSleeper;
import org.joda.time.DateTime;
import org.joda.time.Duration;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link DeleteContactsAndHostsAction}. */
@RunWith(JUnit4.class)
public class DeleteContactsAndHostsActionTest
    extends MapreduceTestCase<DeleteContactsAndHostsAction> {

  @Rule
  public final ExceptionRule thrown = new ExceptionRule();

  @Rule
  public final InjectRule inject = new InjectRule();

  AsyncFlowEnqueuer enqueuer;
  FakeClock clock = new FakeClock(DateTime.parse("2015-01-15T11:22:33Z"));

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
    ofy().clearSessionCache();
  }

  @Before
  public void setup() throws Exception {
    enqueuer = new AsyncFlowEnqueuer();
    enqueuer.asyncDeleteDelay = Duration.ZERO;
    enqueuer.asyncDeletePullQueue = QueueFactory.getQueue(QUEUE_ASYNC_DELETE);
    enqueuer.retrier = new Retrier(new FakeSleeper(clock), 1);

    action = new DeleteContactsAndHostsAction();
    action.clock = clock;
    action.mrRunner = makeDefaultRunner();
    action.response = new FakeResponse();
    action.retrier = new Retrier(new FakeSleeper(clock), 1);
    action.queue = getQueue(QUEUE_ASYNC_DELETE);
    inject.setStaticField(Ofy.class, "clock", clock);

    createTld("tld");
    clock.advanceOneMilli();
  }
  @Test
  public void testSuccess_contact_referencedByActiveDomain_doesNotGetDeleted() throws Exception {
    ContactResource contact = persistContactPendingDelete("blah8221");
    persistResource(newDomainResource("example.tld", contact));
    enqueuer.enqueueAsyncDelete(contact, "TheRegistrar", false);
    runMapreduce();
    ContactResource contactUpdated =
        loadByForeignKey(ContactResource.class, "blah8221", clock.nowUtc());
    assertAboutContacts()
        .that(contactUpdated)
        .doesNotHaveStatusValue(PENDING_DELETE)
        .and()
        .hasDeletionTime(END_OF_TIME);
    DomainResource domainReloaded =
        loadByForeignKey(DomainResource.class, "example.tld", clock.nowUtc());
    assertThat(domainReloaded.getReferencedContacts()).contains(Key.create(contactUpdated));
    HistoryEntry historyEntry =
        getOnlyHistoryEntryOfType(contactUpdated, HistoryEntry.Type.CONTACT_DELETE_FAILURE);
    assertPollMessageFor(
        historyEntry,
        "TheRegistrar",
        "Can't delete contact blah8221 because it is referenced by a domain.");
    assertNoTasksEnqueued(QUEUE_ASYNC_DELETE);
  }

  @Test
  public void testSuccess_contact_notReferenced_getsDeleted_andPiiWipedOut() throws Exception {
    ContactResource contact = persistContactWithPii("jim919");
    enqueuer.enqueueAsyncDelete(contact, "TheRegistrar", false);
    runMapreduce();
    assertThat(loadByForeignKey(ContactResource.class, "jim919", clock.nowUtc())).isNull();
    ContactResource contactAfterDeletion = ofy().load().entity(contact).now();
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
    assertPollMessageFor(historyEntry, "TheRegistrar", "Deleted contact jim919.");
    assertNoTasksEnqueued(QUEUE_ASYNC_DELETE);
  }

  @Test
  public void testSuccess_contactWithPendingTransfer_getsDeleted() throws Exception {
    DateTime transferRequestTime = clock.nowUtc().minusDays(3);
    ContactResource contact =
        persistContactWithPendingTransfer(
            newContactResource("sh8013").asBuilder().addStatusValue(PENDING_DELETE).build(),
            transferRequestTime,
            transferRequestTime.plus(Registry.DEFAULT_TRANSFER_GRACE_PERIOD),
            clock.nowUtc());
    enqueuer.enqueueAsyncDelete(contact, "TheRegistrar", false);
    runMapreduce();
    // Check that the contact is deleted as of now.
    assertThat(loadByForeignKey(ContactResource.class, "sh8013", clock.nowUtc())).isNull();
    // Check that it's still there (it wasn't deleted yesterday) and that it has history.
    ContactResource softDeletedContact =
        loadByForeignKey(ContactResource.class, "sh8013", clock.nowUtc().minusDays(1));
    assertAboutContacts()
        .that(softDeletedContact)
        .hasOneHistoryEntryEachOfTypes(CONTACT_TRANSFER_REQUEST, CONTACT_DELETE);
    assertThat(softDeletedContact.getTransferData().getPendingTransferExpirationTime())
        .isEqualTo(softDeletedContact.getDeletionTime());
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
            Iterables.getOnlyElement(
                    FluentIterable.from(gainingPollMessage.getResponseData())
                        .filter(TransferResponse.class))
                .getTransferStatus())
        .isEqualTo(SERVER_CANCELLED);
    PendingActionNotificationResponse panData =
        getOnlyElement(
            FluentIterable.from(gainingPollMessage.getResponseData())
                .filter(PendingActionNotificationResponse.class));
    assertThat(panData.getTrid())
        .isEqualTo(Trid.create("transferClient-trid", "transferServer-trid"));
    assertThat(panData.getActionResult()).isFalse();
    assertNoTasksEnqueued(QUEUE_ASYNC_DELETE);
  }

  @Test
  public void testSuccess_contact_referencedByDeletedDomain_getsDeleted() throws Exception {
    ContactResource contactUsed = persistContactPendingDelete("blah1234");
    persistResource(
        newDomainResource("example.tld", contactUsed)
            .asBuilder()
            .setDeletionTime(clock.nowUtc().minusDays(3))
            .build());
    enqueuer.enqueueAsyncDelete(contactUsed, "TheRegistrar", false);
    runMapreduce();
    assertThat(loadByForeignKey(ContactResource.class, "blah1234", clock.nowUtc())).isNull();
    ContactResource contactBeforeDeletion =
        loadByForeignKey(ContactResource.class, "blah1234", clock.nowUtc().minusDays(1));
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
    assertPollMessageFor(historyEntry, "TheRegistrar", "Deleted contact blah1234.");
    assertNoTasksEnqueued(QUEUE_ASYNC_DELETE);
  }

  @Test
  public void testSuccess_contact_notRequestedByOwner_doesNotGetDeleted() throws Exception {
    ContactResource contact = persistContactPendingDelete("jane0991");
    enqueuer.enqueueAsyncDelete(contact, "OtherRegistrar", false);
    runMapreduce();
    ContactResource contactAfter =
        loadByForeignKey(ContactResource.class, "jane0991", clock.nowUtc());
    assertAboutContacts()
        .that(contactAfter)
        .doesNotHaveStatusValue(PENDING_DELETE)
        .and()
        .hasDeletionTime(END_OF_TIME);
    HistoryEntry historyEntry = getOnlyHistoryEntryOfType(contactAfter, CONTACT_DELETE_FAILURE);
    assertPollMessageFor(
        historyEntry,
        "OtherRegistrar",
        "Can't delete contact jane0991 because it was transferred prior to deletion.");
    assertNoTasksEnqueued(QUEUE_ASYNC_DELETE);
  }

  @Test
  public void testSuccess_contact_notRequestedByOwner_isSuperuser_getsDeleted() throws Exception {
    ContactResource contact = persistContactWithPii("nate007");
    enqueuer.enqueueAsyncDelete(contact, "OtherRegistrar", true);
    runMapreduce();
    assertThat(loadByForeignKey(ContactResource.class, "nate007", clock.nowUtc())).isNull();
    ContactResource contactAfterDeletion = ofy().load().entity(contact).now();
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
    assertPollMessageFor(historyEntry, "OtherRegistrar", "Deleted contact nate007.");
    assertNoTasksEnqueued(QUEUE_ASYNC_DELETE);
  }

  @Test
  public void testSuccess_targetResourcesDontExist_areDelayedForADay() throws Exception {
    ContactResource contactNotSaved = newContactResource("somecontact");
    HostResource hostNotSaved = newHostResource("a11.blah.foo");
    enqueuer.enqueueAsyncDelete(contactNotSaved, "TheRegistrar", false);
    enqueuer.enqueueAsyncDelete(hostNotSaved, "TheRegistrar", false);
    runMapreduce();
    String payloadFormat = "resourceKey=%s&requestingClientId=TheRegistrar&isSuperuser=false";
    assertTasksEnqueued(
        QUEUE_ASYNC_DELETE,
        new TaskMatcher()
            .payload(String.format(payloadFormat, Key.create(contactNotSaved).getString()))
            .etaDelta(standardHours(23), standardHours(25)),
        new TaskMatcher()
            .payload(String.format(payloadFormat, Key.create(hostNotSaved).getString()))
            .etaDelta(standardHours(23), standardHours(25)));
  }

  @Test
  public void testSuccess_unparseableTasks_areDelayedForADay() throws Exception {
    TaskOptions task =
        TaskOptions.Builder.withMethod(Method.PULL).param("gobbledygook", "kljhadfgsd9f7gsdfh");
    getQueue(QUEUE_ASYNC_DELETE).add(task);
    runMapreduce();
    assertTasksEnqueued(
        QUEUE_ASYNC_DELETE,
        new TaskMatcher()
            .payload("gobbledygook=kljhadfgsd9f7gsdfh")
            .etaDelta(standardHours(23), standardHours(25)));
  }

  @Test
  public void testSuccess_resourcesNotInPendingDelete_areSkipped() throws Exception {
    ContactResource contact = persistActiveContact("blah2222");
    HostResource host = persistActiveHost("rustles.your.jimmies");
    enqueuer.enqueueAsyncDelete(contact, "TheRegistrar", false);
    enqueuer.enqueueAsyncDelete(host, "TheRegistrar", false);
    runMapreduce();
    assertThat(loadByForeignKey(ContactResource.class, "blah2222", clock.nowUtc()))
        .isEqualTo(contact);
    assertThat(loadByForeignKey(HostResource.class, "rustles.your.jimmies", clock.nowUtc()))
        .isEqualTo(host);
    assertNoTasksEnqueued(QUEUE_ASYNC_DELETE);
  }

  @Test
  public void testSuccess_alreadyDeletedResources_areSkipped() throws Exception {
    ContactResource contactDeleted = persistDeletedContact("blah1236", clock.nowUtc().minusDays(2));
    HostResource hostDeleted = persistDeletedHost("a.lim.lop", clock.nowUtc().minusDays(3));
    enqueuer.enqueueAsyncDelete(contactDeleted, "TheRegistrar", false);
    enqueuer.enqueueAsyncDelete(hostDeleted, "TheRegistrar", false);
    runMapreduce();
    assertThat(ofy().load().entity(contactDeleted).now()).isEqualTo(contactDeleted);
    assertThat(ofy().load().entity(hostDeleted).now()).isEqualTo(hostDeleted);
    assertNoTasksEnqueued(QUEUE_ASYNC_DELETE);
  }

  @Test
  public void testSuccess_host_referencedByActiveDomain_doesNotGetDeleted() throws Exception {
    HostResource host = persistHostPendingDelete("ns1.example.tld");
    persistUsedDomain("example.tld", persistActiveContact("abc456"), host);
    enqueuer.enqueueAsyncDelete(host, "TheRegistrar", false);
    runMapreduce();
    HostResource hostAfter =
        loadByForeignKey(HostResource.class, "ns1.example.tld", clock.nowUtc());
    assertAboutHosts()
        .that(hostAfter)
        .doesNotHaveStatusValue(PENDING_DELETE)
        .and()
        .hasDeletionTime(END_OF_TIME);
    DomainResource domain = loadByForeignKey(DomainResource.class, "example.tld", clock.nowUtc());
    assertThat(domain.getNameservers()).contains(Key.create(hostAfter));
    HistoryEntry historyEntry = getOnlyHistoryEntryOfType(hostAfter, HOST_DELETE_FAILURE);
    assertPollMessageFor(
        historyEntry,
        "TheRegistrar",
        "Can't delete host ns1.example.tld because it is referenced by a domain.");
    assertNoTasksEnqueued(QUEUE_ASYNC_DELETE);
  }

  @Test
  public void testSuccess_host_notReferenced_getsDeleted() throws Exception {
    HostResource host = persistHostPendingDelete("ns2.example.tld");
    enqueuer.enqueueAsyncDelete(host, "TheRegistrar", false);
    runMapreduce();
    assertThat(loadByForeignKey(HostResource.class, "ns2.example.tld", clock.nowUtc())).isNull();
    HostResource hostBeforeDeletion =
        loadByForeignKey(HostResource.class, "ns2.example.tld", clock.nowUtc().minusDays(1));
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
    assertPollMessageFor(historyEntry, "TheRegistrar", "Deleted host ns2.example.tld.");
    assertNoTasksEnqueued(QUEUE_ASYNC_DELETE);
  }

  @Test
  public void testSuccess_host_referencedByDeletedDomain_getsDeleted() throws Exception {
    HostResource host = persistHostPendingDelete("ns1.example.tld");
    persistResource(
        newDomainResource("example.tld")
            .asBuilder()
            .setNameservers(ImmutableSet.of(Key.create(host)))
            .setDeletionTime(clock.nowUtc().minusDays(5))
            .build());
    enqueuer.enqueueAsyncDelete(host, "TheRegistrar", false);
    runMapreduce();
    assertThat(loadByForeignKey(HostResource.class, "ns1.example.tld", clock.nowUtc())).isNull();
    HostResource hostBeforeDeletion =
        loadByForeignKey(HostResource.class, "ns1.example.tld", clock.nowUtc().minusDays(1));
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
    assertPollMessageFor(historyEntry, "TheRegistrar", "Deleted host ns1.example.tld.");
    assertNoTasksEnqueued(QUEUE_ASYNC_DELETE);
  }

  @Test
  public void testSuccess_subordinateHost_getsDeleted() throws Exception {
    DomainResource domain =
        persistResource(
            newDomainResource("example.tld")
                .asBuilder()
                .setSubordinateHosts(ImmutableSet.of("ns2.example.tld"))
                .build());
    HostResource host =
        persistResource(
            persistHostPendingDelete("ns2.example.tld")
                .asBuilder()
                .setSuperordinateDomain(Key.create(domain))
                .build());
    enqueuer.enqueueAsyncDelete(host, "TheRegistrar", false);
    runMapreduce();
    // Check that the host is deleted as of now.
    assertThat(loadByForeignKey(HostResource.class, "ns2.example.tld", clock.nowUtc())).isNull();
    assertNoBillingEvents();
    assertThat(
            loadByForeignKey(DomainResource.class, "example.tld", clock.nowUtc())
                .getSubordinateHosts())
        .isEmpty();
    assertDnsTasksEnqueued("ns2.example.tld");
    HostResource hostBeforeDeletion =
        loadByForeignKey(HostResource.class, "ns2.example.tld", clock.nowUtc().minusDays(1));
    assertAboutHosts()
        .that(hostBeforeDeletion)
        .isNotActiveAt(clock.nowUtc())
        .and()
        .hasExactlyStatusValues(StatusValue.OK)
        .and()
        .hasOnlyOneHistoryEntryWhich()
        .hasType(HOST_DELETE);
    HistoryEntry historyEntry = getOnlyHistoryEntryOfType(hostBeforeDeletion, HOST_DELETE);
    assertPollMessageFor(historyEntry, "TheRegistrar", "Deleted host ns2.example.tld.");
    assertNoTasksEnqueued(QUEUE_ASYNC_DELETE);
  }

  @Test
  public void testSuccess_host_notRequestedByOwner_doesNotGetDeleted() throws Exception {
    HostResource host = persistHostPendingDelete("ns2.example.tld");
    enqueuer.enqueueAsyncDelete(host, "OtherRegistrar", false);
    runMapreduce();
    HostResource hostAfter =
        loadByForeignKey(HostResource.class, "ns2.example.tld", clock.nowUtc());
    assertAboutHosts()
        .that(hostAfter)
        .doesNotHaveStatusValue(PENDING_DELETE)
        .and()
        .hasDeletionTime(END_OF_TIME);
    HistoryEntry historyEntry = getOnlyHistoryEntryOfType(host, HOST_DELETE_FAILURE);
    assertPollMessageFor(
        historyEntry,
        "OtherRegistrar",
        "Can't delete host ns2.example.tld because it was transferred prior to deletion.");
    assertNoTasksEnqueued(QUEUE_ASYNC_DELETE);
  }

  @Test
  public void testSuccess_host_notRequestedByOwner_isSuperuser_getsDeleted() throws Exception {
    HostResource host = persistHostPendingDelete("ns66.example.tld");
    enqueuer.enqueueAsyncDelete(host, "OtherRegistrar", true);
    runMapreduce();
    assertThat(loadByForeignKey(HostResource.class, "ns66.example.tld", clock.nowUtc())).isNull();
    HostResource hostBeforeDeletion =
        loadByForeignKey(HostResource.class, "ns66.example.tld", clock.nowUtc().minusDays(1));
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
    assertPollMessageFor(historyEntry, "OtherRegistrar", "Deleted host ns66.example.tld.");
    assertNoTasksEnqueued(QUEUE_ASYNC_DELETE);
  }

  @Test
  public void testSuccess_deleteABunchOfContactsAndHosts_butNotSome() throws Exception {
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
      enqueuer.enqueueAsyncDelete(resource, "TheRegistrar", false);
    }
    runMapreduce();
    for (EppResource resource : ImmutableList.<EppResource>of(c1, c2, c3, h1, h2, h3)) {
      EppResource loaded = ofy().load().entity(resource).now();
      assertThat(loaded.getDeletionTime()).isLessThan(DateTime.now(UTC));
      assertThat(loaded.getStatusValues()).doesNotContain(PENDING_DELETE);
    }
    for (EppResource resource : ImmutableList.<EppResource>of(c4, h4)) {
      EppResource loaded = ofy().load().entity(resource).now();
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
   * client id, and message.
   */
  private static void assertPollMessageFor(HistoryEntry historyEntry, String clientId, String msg) {
    PollMessage.OneTime pollMessage = (OneTime) getOnlyPollMessageForHistoryEntry(historyEntry);
    assertThat(msg).isEqualTo(pollMessage.getMsg());
    assertThat(clientId).isEqualTo(pollMessage.getClientId());
    assertThat(pollMessage.getClientId()).isEqualTo(clientId);
  }

  private static ContactResource persistContactPendingDelete(String contactId) {
    return persistResource(
        newContactResource(contactId).asBuilder().addStatusValue(PENDING_DELETE).build());
  }

  private static HostResource persistHostPendingDelete(String hostName) {
    return persistResource(
        newHostResource(hostName).asBuilder().addStatusValue(PENDING_DELETE).build());
  }

  private static DomainResource persistUsedDomain(
      String domainName, ContactResource contact, HostResource host) {
    return persistResource(
        newDomainResource(domainName, contact)
            .asBuilder()
            .setNameservers(ImmutableSet.of(Key.create(host)))
            .build());
  }
}
