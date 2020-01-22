// Copyright 2018 The Nomulus Authors. All Rights Reserved.
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
import static com.google.common.truth.Truth.assertThat;
import static google.registry.batch.AsyncTaskEnqueuer.PARAM_REQUESTED_TIME;
import static google.registry.batch.AsyncTaskEnqueuer.PARAM_RESOURCE_KEY;
import static google.registry.batch.AsyncTaskEnqueuer.PATH_RESAVE_ENTITY;
import static google.registry.batch.AsyncTaskEnqueuer.QUEUE_ASYNC_ACTIONS;
import static google.registry.batch.AsyncTaskEnqueuer.QUEUE_ASYNC_DELETE;
import static google.registry.batch.AsyncTaskEnqueuer.QUEUE_ASYNC_HOST_RENAME;
import static google.registry.model.ofy.ObjectifyService.ofy;
import static google.registry.testing.DatastoreHelper.createTld;
import static google.registry.testing.DatastoreHelper.newDomainBase;
import static google.registry.testing.DatastoreHelper.persistActiveContact;
import static google.registry.testing.DatastoreHelper.persistDomainWithDependentResources;
import static google.registry.testing.DatastoreHelper.persistDomainWithPendingTransfer;
import static google.registry.testing.DatastoreHelper.persistResource;
import static google.registry.testing.TaskQueueHelper.assertTasksEnqueued;
import static org.joda.time.Duration.standardDays;
import static org.joda.time.Duration.standardSeconds;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.googlecode.objectify.Key;
import google.registry.model.ImmutableObject;
import google.registry.model.domain.DomainBase;
import google.registry.model.domain.GracePeriod;
import google.registry.model.domain.rgp.GracePeriodStatus;
import google.registry.model.eppcommon.StatusValue;
import google.registry.model.ofy.Ofy;
import google.registry.request.Response;
import google.registry.testing.AppEngineRule;
import google.registry.testing.FakeClock;
import google.registry.testing.FakeSleeper;
import google.registry.testing.InjectRule;
import google.registry.testing.ShardableTestCase;
import google.registry.testing.TaskQueueHelper.TaskMatcher;
import google.registry.util.AppEngineServiceUtils;
import google.registry.util.Retrier;
import org.joda.time.DateTime;
import org.joda.time.Duration;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

/** Unit tests for {@link ResaveEntityAction}. */
@RunWith(JUnit4.class)
public class ResaveEntityActionTest extends ShardableTestCase {

  @Rule
  public final AppEngineRule appEngine =
      AppEngineRule.builder().withDatastore().withTaskQueue().build();

  @Rule public final InjectRule inject = new InjectRule();
  @Rule public final MockitoRule mocks = MockitoJUnit.rule();

  @Mock private AppEngineServiceUtils appEngineServiceUtils;
  @Mock private Response response;
  private final FakeClock clock = new FakeClock(DateTime.parse("2016-02-11T10:00:00Z"));
  private AsyncTaskEnqueuer asyncTaskEnqueuer;

  @Before
  public void before() {
    inject.setStaticField(Ofy.class, "clock", clock);
    when(appEngineServiceUtils.getServiceHostname("backend")).thenReturn("backend.hostname.fake");
    asyncTaskEnqueuer =
        new AsyncTaskEnqueuer(
            getQueue(QUEUE_ASYNC_ACTIONS),
            getQueue(QUEUE_ASYNC_DELETE),
            getQueue(QUEUE_ASYNC_HOST_RENAME),
            Duration.ZERO,
            appEngineServiceUtils,
            new Retrier(new FakeSleeper(clock), 1));
    createTld("tld");
  }

  private void runAction(
      Key<ImmutableObject> resourceKey,
      DateTime requestedTime,
      ImmutableSortedSet<DateTime> resaveTimes) {
    ResaveEntityAction action =
        new ResaveEntityAction(
            resourceKey, requestedTime, resaveTimes, asyncTaskEnqueuer, response);
    action.run();
  }

  @Test
  public void test_domainPendingTransfer_isResavedAndTransferCompleted() {
    DomainBase domain =
        persistDomainWithPendingTransfer(
            persistDomainWithDependentResources(
                "domain",
                "tld",
                persistActiveContact("jd1234"),
                DateTime.parse("2016-02-06T10:00:00Z"),
                DateTime.parse("2016-02-06T10:00:00Z"),
                DateTime.parse("2017-01-02T10:11:00Z")),
            DateTime.parse("2016-02-06T10:00:00Z"),
            DateTime.parse("2016-02-11T10:00:00Z"),
            DateTime.parse("2017-01-02T10:11:00Z"));
    clock.advanceOneMilli();
    assertThat(domain.getCurrentSponsorClientId()).isEqualTo("TheRegistrar");
    runAction(Key.create(domain), DateTime.parse("2016-02-06T10:00:01Z"), ImmutableSortedSet.of());
    DomainBase resavedDomain = ofy().load().entity(domain).now();
    assertThat(resavedDomain.getCurrentSponsorClientId()).isEqualTo("NewRegistrar");
    verify(response).setPayload("Entity re-saved.");
  }

  @Test
  public void test_domainPendingDeletion_isResavedAndReenqueued() {
    DomainBase domain =
        persistResource(
            newDomainBase("domain.tld")
                .asBuilder()
                .setDeletionTime(clock.nowUtc().plusDays(35))
                .setStatusValues(ImmutableSet.of(StatusValue.PENDING_DELETE))
                .setGracePeriods(
                    ImmutableSet.of(
                        GracePeriod.createWithoutBillingEvent(
                            GracePeriodStatus.REDEMPTION,
                            clock.nowUtc().plusDays(30),
                            "TheRegistrar")))
                .build());
    clock.advanceBy(standardDays(30));
    DateTime requestedTime = clock.nowUtc();

    assertThat(domain.getGracePeriods()).isNotEmpty();
    runAction(Key.create(domain), requestedTime, ImmutableSortedSet.of(requestedTime.plusDays(5)));
    DomainBase resavedDomain = ofy().load().entity(domain).now();
    assertThat(resavedDomain.getGracePeriods()).isEmpty();

    assertTasksEnqueued(
        QUEUE_ASYNC_ACTIONS,
        new TaskMatcher()
            .url(PATH_RESAVE_ENTITY)
            .method("POST")
            .header("Host", "backend.hostname.fake")
            .header("content-type", "application/x-www-form-urlencoded")
            .param(PARAM_RESOURCE_KEY, Key.create(resavedDomain).getString())
            .param(PARAM_REQUESTED_TIME, requestedTime.toString())
            .etaDelta(
                standardDays(5).minus(standardSeconds(30)),
                standardDays(5).plus(standardSeconds(30))));
  }
}
