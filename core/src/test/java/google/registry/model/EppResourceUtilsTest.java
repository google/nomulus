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

package google.registry.model;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.model.EppResourceUtils.loadAtPointInTime;
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.newHostResource;
import static google.registry.testing.DatabaseHelper.persistNewRegistrars;
import static google.registry.testing.DatabaseHelper.persistResource;
import static google.registry.testing.DatabaseHelper.persistResourceWithCommitLog;
import static google.registry.util.DateTimeUtils.START_OF_TIME;
import static org.joda.time.DateTimeZone.UTC;

import google.registry.model.host.HostResource;
import google.registry.model.ofy.Ofy;
import google.registry.testing.AppEngineExtension;
import google.registry.testing.DualDatabaseTest;
import google.registry.testing.FakeClock;
import google.registry.testing.InjectExtension;
import google.registry.testing.TestOfyAndSql;
import google.registry.testing.TestOfyOnly;
import org.joda.time.DateTime;
import org.joda.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Tests for {@link EppResourceUtils}. */
@DualDatabaseTest
class EppResourceUtilsTest {

  private final FakeClock clock = new FakeClock(DateTime.now(UTC));

  @RegisterExtension
  public final AppEngineExtension appEngine =
      AppEngineExtension.builder()
          .withDatastoreAndCloudSql()
          .withClock(clock)
          .withTaskQueue()
          .build();

  @RegisterExtension public final InjectExtension inject = new InjectExtension();

  @BeforeEach
  void beforeEach() {
    createTld("tld");
    inject.setStaticField(Ofy.class, "clock", clock);
  }

  @TestOfyAndSql
  void testLoadAtPointInTime_beforeCreated_returnsNull() {
    clock.advanceOneMilli();
    // Don't save a commit log, we shouldn't need one.
    HostResource host = persistResource(
        newHostResource("ns1.cat.tld").asBuilder()
            .setCreationTimeForTest(clock.nowUtc())
            .build());
    assertThat(loadAtPointInTime(host, clock.nowUtc().minus(Duration.millis(1)))).isNull();
  }

  @TestOfyAndSql
  void testLoadAtPointInTime_atOrAfterLastAutoUpdateTime_returnsResource() {
    clock.advanceOneMilli();
    // Don't save a commit log, we shouldn't need one.
    HostResource host = persistResource(
        newHostResource("ns1.cat.tld").asBuilder()
            .setCreationTimeForTest(START_OF_TIME)
            .build());
    assertThat(loadAtPointInTime(host, clock.nowUtc())).isEqualTo(host);
  }

  @TestOfyOnly
  void testLoadAtPointInTime_usingIntactRevisionHistory_returnsMutationValue() {
    persistNewRegistrars("OLD", "NEW");
    clock.advanceOneMilli();
    // Save resource with a commit log that we can read in later as a revisions map value.
    HostResource oldHost =
        persistResourceWithCommitLog(
            newHostResource("ns1.cat.tld")
                .asBuilder()
                .setCreationTimeForTest(START_OF_TIME)
                .setPersistedCurrentSponsorRegistrarId("OLD")
                .build());
    // Advance a day so that the next created revision entry doesn't overwrite the existing one.
    clock.advanceBy(Duration.standardDays(1));
    // Overwrite the current host with one that has different data.
    HostResource currentHost =
        persistResource(oldHost.asBuilder().setPersistedCurrentSponsorRegistrarId("NEW").build());
    // Load at the point in time just before the latest update; the floor entry of the revisions
    // map should point to the manifest for the first save, so we should get the old host.
    assertThat(loadAtPointInTime(currentHost, clock.nowUtc().minusMillis(1))).isEqualTo(oldHost);
  }

  @TestOfyOnly
  void testLoadAtPointInTime_brokenRevisionHistory_returnsResourceAsIs() {
    // Don't save a commit log since we want to test the handling of a broken revisions key.
    HostResource oldHost =
        persistResource(
            newHostResource("ns1.cat.tld")
                .asBuilder()
                .setCreationTimeForTest(START_OF_TIME)
                .setPersistedCurrentSponsorRegistrarId("OLD")
                .build());
    // Advance a day so that the next created revision entry doesn't overwrite the existing one.
    clock.advanceBy(Duration.standardDays(1));
    // Overwrite the existing resource to force revisions map use.
    HostResource host =
        persistResource(oldHost.asBuilder().setPersistedCurrentSponsorRegistrarId("NEW").build());
    // Load at the point in time just before the latest update; the old host is not recoverable
    // (revisions map link is broken, and guessing using the oldest revision map entry finds the
    // same broken link), so just returns the current host.
    assertThat(loadAtPointInTime(host, clock.nowUtc().minusMillis(1))).isEqualTo(host);
  }

  @TestOfyOnly
  void testLoadAtPointInTime_fallback_returnsMutationValueForOldestRevision() {
    clock.advanceOneMilli();
    // Save a commit log that we can fall back to.
    HostResource oldHost =
        persistResourceWithCommitLog(
            newHostResource("ns1.cat.tld")
                .asBuilder()
                .setCreationTimeForTest(START_OF_TIME)
                .setPersistedCurrentSponsorRegistrarId("OLD")
                .build());
    // Advance a day so that the next created revision entry doesn't overwrite the existing one.
    clock.advanceBy(Duration.standardDays(1));
    // Overwrite the current host with one that has different data.
    HostResource currentHost =
        persistResource(oldHost.asBuilder().setPersistedCurrentSponsorRegistrarId("NEW").build());
    // Load at the point in time before the first update; there will be no floor entry for the
    // revisions map, so give up and return the oldest revision entry's mutation value (the old host
    // data).
    assertThat(loadAtPointInTime(currentHost, clock.nowUtc().minusDays(2))).isEqualTo(oldHost);
  }

  @TestOfyOnly
  void testLoadAtPointInTime_ultimateFallback_onlyOneRevision_returnsCurrentResource() {
    clock.advanceOneMilli();
    // Don't save a commit log; we want to test that we load from the current resource.
    HostResource host =
        persistResource(
            newHostResource("ns1.cat.tld")
                .asBuilder()
                .setCreationTimeForTest(START_OF_TIME)
                .setPersistedCurrentSponsorRegistrarId("OLD")
                .build());
    // Load at the point in time before the first save; there will be no floor entry for the
    // revisions map.  Since the oldest revision entry is the only (i.e. current) revision, return
    // the resource.
    assertThat(loadAtPointInTime(host, clock.nowUtc().minusMillis(1))).isEqualTo(host);
  }

  @TestOfyOnly
  void testLoadAtPointInTime_moreThanThirtyDaysInPast_historyIsPurged() {
    clock.advanceOneMilli();
    HostResource host =
        persistResourceWithCommitLog(newHostResource("ns1.example.net"));
    assertThat(host.getRevisions()).hasSize(1);
    clock.advanceBy(Duration.standardDays(31));
    host = persistResourceWithCommitLog(host);
    assertThat(host.getRevisions()).hasSize(2);
    clock.advanceBy(Duration.standardDays(31));
    host = persistResourceWithCommitLog(host);
    assertThat(host.getRevisions()).hasSize(2);
    // Even though there is no revision, make a best effort guess to use the oldest revision.
    assertThat(
            loadAtPointInTime(host, clock.nowUtc().minus(Duration.standardDays(32)))

                .getUpdateTimestamp()
                .getTimestamp())
        .isEqualTo(host.getRevisions().firstKey());
  }
}
