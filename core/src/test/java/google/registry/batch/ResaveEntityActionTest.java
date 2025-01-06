// Copyright 2025 The Nomulus Authors. All Rights Reserved.
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

import static com.google.common.truth.Truth.assertThat;
import static google.registry.batch.AsyncTaskEnqueuer.PARAM_REQUESTED_TIME;
import static google.registry.batch.AsyncTaskEnqueuer.PARAM_RESOURCE_KEY;
import static google.registry.batch.AsyncTaskEnqueuer.QUEUE_ASYNC_ACTIONS;
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.loadByEntity;
import static google.registry.testing.DatabaseHelper.newDomain;
import static google.registry.testing.DatabaseHelper.persistActiveContact;
import static google.registry.testing.DatabaseHelper.persistDomainWithDependentResources;
import static google.registry.testing.DatabaseHelper.persistDomainWithPendingTransfer;
import static google.registry.testing.DatabaseHelper.persistResource;
import static org.joda.time.Duration.standardDays;
import static org.mockito.Mockito.verify;

import com.google.cloud.tasks.v2.HttpMethod;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import google.registry.model.domain.Domain;
import google.registry.model.domain.GracePeriod;
import google.registry.model.domain.rgp.GracePeriodStatus;
import google.registry.model.eppcommon.StatusValue;
import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.persistence.transaction.JpaTestExtensions.JpaIntegrationTestExtension;
import google.registry.request.Response;
import google.registry.testing.CloudTasksHelper;
import google.registry.testing.CloudTasksHelper.TaskMatcher;
import google.registry.testing.FakeClock;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/** Unit tests for {@link ResaveEntityAction}. */
@ExtendWith(MockitoExtension.class)
public class ResaveEntityActionTest {

  @RegisterExtension
  final JpaIntegrationTestExtension jpa =
      new JpaTestExtensions.Builder().buildIntegrationTestExtension();

  @Mock private Response response;
  private final FakeClock clock = new FakeClock(DateTime.parse("2016-02-11T10:00:00Z"));
  private AsyncTaskEnqueuer asyncTaskEnqueuer;
  private final CloudTasksHelper cloudTasksHelper = new CloudTasksHelper(clock);

  @BeforeEach
  void beforeEach() {
    asyncTaskEnqueuer =
        AsyncTaskEnqueuerTest.createForTesting(cloudTasksHelper.getTestCloudTasksUtils());
    createTld("tld");
  }

  private void runAction(
      String resourceKey, DateTime requestedTime, ImmutableSortedSet<DateTime> resaveTimes) {
    ResaveEntityAction action =
        new ResaveEntityAction(
            resourceKey, requestedTime, resaveTimes, asyncTaskEnqueuer, response);
    action.run();
  }

  @MockitoSettings(strictness = Strictness.LENIENT)
  @Test
  void test_domainPendingTransfer_isResavedAndTransferCompleted() {
    Domain domain =
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
    assertThat(domain.getCurrentSponsorRegistrarId()).isEqualTo("TheRegistrar");
    runAction(
        domain.createVKey().stringify(),
        DateTime.parse("2016-02-06T10:00:01Z"),
        ImmutableSortedSet.of());
    Domain resavedDomain = loadByEntity(domain);
    assertThat(resavedDomain.getCurrentSponsorRegistrarId()).isEqualTo("NewRegistrar");
    verify(response).setPayload("Entity re-saved.");
  }

  @Test
  void test_domainPendingDeletion_isResavedAndReenqueued() {
    Domain newDomain = newDomain("domain.tld");
    Domain domain =
        persistResource(
            newDomain
                .asBuilder()
                .setDeletionTime(clock.nowUtc().plusDays(35))
                .setStatusValues(ImmutableSet.of(StatusValue.PENDING_DELETE))
                .setGracePeriods(
                    ImmutableSet.of(
                        GracePeriod.createWithoutBillingEvent(
                            GracePeriodStatus.REDEMPTION,
                            newDomain.getRepoId(),
                            clock.nowUtc().plusDays(30),
                            "TheRegistrar")))
                .build());
    clock.advanceBy(standardDays(30));
    DateTime requestedTime = clock.nowUtc();

    assertThat(domain.getGracePeriods()).isNotEmpty();
    runAction(
        domain.createVKey().stringify(),
        requestedTime,
        ImmutableSortedSet.of(requestedTime.plusDays(5)));
    Domain resavedDomain = loadByEntity(domain);
    assertThat(resavedDomain.getGracePeriods()).isEmpty();

    cloudTasksHelper.assertTasksEnqueued(
        QUEUE_ASYNC_ACTIONS,
        new TaskMatcher()
            .path(ResaveEntityAction.PATH)
            .method(HttpMethod.POST)
            .service("backend")
            .header("content-type", "application/x-www-form-urlencoded")
            .param(PARAM_RESOURCE_KEY, resavedDomain.createVKey().stringify())
            .param(PARAM_REQUESTED_TIME, requestedTime.toString())
            .scheduleTime(clock.nowUtc().plus(standardDays(5))));
  }

  @Test
  void test_queuedTaskForNonExistentDomain_failsPermanently() {
    DateTime requestedTime = clock.nowUtc();
    // It should complete its run without throwing an exception (that would cause a retry) ...
    runAction(
        newDomain("nonexistent.tld").createVKey().stringify(),
        requestedTime,
        ImmutableSortedSet.of(requestedTime.plusDays(5)));
    // ... and it shouldn't enqueue the subsequent re-save 5 days later.
    cloudTasksHelper.assertNoTasksEnqueued(QUEUE_ASYNC_ACTIONS);
  }
}
