// Copyright 2016 The Domain Registry Authors. All Rights Reserved.
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
import static google.registry.flows.picker.FlowPicker.getFlowClass;
import static google.registry.model.EppResourceUtils.loadAtPointInTime;
import static google.registry.model.ofy.ObjectifyService.ofy;
import static google.registry.testing.DatastoreHelper.createTld;
import static google.registry.testing.DatastoreHelper.newHostResource;
import static google.registry.testing.DatastoreHelper.persistActiveContact;
import static google.registry.testing.DatastoreHelper.persistActiveHost;
import static google.registry.testing.DatastoreHelper.persistResource;
import static google.registry.testing.DatastoreHelper.persistResourceWithCommitLog;
import static google.registry.util.DateTimeUtils.START_OF_TIME;
import static org.joda.time.DateTimeZone.UTC;
import static org.joda.time.Duration.standardDays;

import com.googlecode.objectify.Key;

import google.registry.flows.FlowRunner;
import google.registry.flows.FlowRunner.CommitMode;
import google.registry.flows.FlowRunner.UserPrivileges;
import google.registry.flows.SessionMetadata;
import google.registry.model.domain.DomainResource;
import google.registry.model.eppcommon.Trid;
import google.registry.model.host.HostResource;
import google.registry.model.ofy.Ofy;
import google.registry.testing.AppEngineRule;
import google.registry.testing.EppLoader;
import google.registry.testing.ExceptionRule;
import google.registry.testing.FakeClock;
import google.registry.testing.InjectRule;
import google.registry.testing.TestSessionMetadata;

import org.joda.time.DateTime;
import org.joda.time.Duration;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link EppResourceUtils}. */
@RunWith(JUnit4.class)
public class EppResourceUtilsTest {

  @Rule
  public final AppEngineRule appEngine = AppEngineRule.builder()
      .withDatastore()
      .withTaskQueue()
      .build();

  @Rule
  public final ExceptionRule thrown = new ExceptionRule();

  @Rule
  public final InjectRule inject = new InjectRule();

  private final FakeClock clock = new FakeClock(DateTime.now(UTC));
  private EppLoader eppLoader;

  @Before
  public void init() throws Exception {
    createTld("tld");
    inject.setStaticField(Ofy.class, "clock", clock);
  }

  private void runFlow() throws Exception {
    SessionMetadata sessionMetadata = new TestSessionMetadata();
    sessionMetadata.setClientId("TheRegistrar");
    new FlowRunner(
        getFlowClass(eppLoader.getEpp()),
        eppLoader.getEpp(),
        Trid.create(null, "server-trid"),
        sessionMetadata,
        "<xml></xml>".getBytes(),
        null,
        clock)
            .run(CommitMode.LIVE, UserPrivileges.NORMAL);
  }

  /** Test that update flow creates commit logs needed to reload at any arbitrary time. */
  @Test
  public void testLoadAtPointInTime() throws Exception {
    clock.setTo(DateTime.parse("1984-12-18T12:30Z"));  // not midnight

    persistActiveHost("ns1.example.net");
    persistActiveHost("ns2.example.net");
    persistActiveContact("jd1234");
    persistActiveContact("sh8013");

    clock.advanceBy(standardDays(1));
    DateTime timeAtCreate = clock.nowUtc();
    clock.setTo(timeAtCreate);
    eppLoader = new EppLoader(this, "domain_create.xml");
    runFlow();
    ofy().clearSessionCache();
    Key<DomainResource> key = Key.create(ofy().load().type(DomainResource.class).first().now());
    DomainResource domainAfterCreate = ofy().load().key(key).now();
    assertThat(domainAfterCreate.getFullyQualifiedDomainName()).isEqualTo("example.tld");

    clock.advanceBy(standardDays(2));
    DateTime timeAtFirstUpdate = clock.nowUtc();
    eppLoader = new EppLoader(this, "domain_update_dsdata_add.xml");
    runFlow();
    ofy().clearSessionCache();

    DomainResource domainAfterFirstUpdate = ofy().load().key(key).now();
    assertThat(domainAfterCreate).isNotEqualTo(domainAfterFirstUpdate);

    clock.advanceOneMilli();  // same day as first update
    DateTime timeAtSecondUpdate = clock.nowUtc();
    eppLoader = new EppLoader(this, "domain_update_dsdata_rem.xml");
    runFlow();
    ofy().clearSessionCache();
    DomainResource domainAfterSecondUpdate = ofy().load().key(key).now();

    clock.advanceBy(standardDays(2));
    DateTime timeAtDelete = clock.nowUtc();  // before 'add' grace period ends
    eppLoader = new EppLoader(this, "domain_delete.xml");
    runFlow();
    ofy().clearSessionCache();

    assertThat(domainAfterFirstUpdate).isNotEqualTo(domainAfterSecondUpdate);

    // Point-in-time can only rewind an object from the current version, not roll forward.
    DomainResource latest = ofy().load().key(key).now();

    // Creation time has millisecond granularity due to isActive() check.
    ofy().clearSessionCache();
    assertThat(loadAtPointInTime(latest, timeAtCreate.minusMillis(1)).now()).isNull();
    assertThat(loadAtPointInTime(latest, timeAtCreate).now()).isNotNull();
    assertThat(loadAtPointInTime(latest, timeAtCreate.plusMillis(1)).now()).isNotNull();

    ofy().clearSessionCache();
    assertThat(loadAtPointInTime(latest, timeAtCreate.plusDays(1)).now())
        .isEqualTo(domainAfterCreate);

    // Both updates happened on the same day. Since the revisions field has day granularity, the
    // reference to the first update should have been overwritten by the second, and its timestamp
    // rolled forward. So we have to fall back to the last revision before midnight.
    ofy().clearSessionCache();
    assertThat(loadAtPointInTime(latest, timeAtFirstUpdate).now())
        .isEqualTo(domainAfterCreate);

    ofy().clearSessionCache();
    assertThat(loadAtPointInTime(latest, timeAtSecondUpdate).now())
        .isEqualTo(domainAfterSecondUpdate);

    ofy().clearSessionCache();
    assertThat(loadAtPointInTime(latest, timeAtSecondUpdate.plusDays(1)).now())
        .isEqualTo(domainAfterSecondUpdate);

    // Deletion time has millisecond granularity due to isActive() check.
    ofy().clearSessionCache();
    assertThat(loadAtPointInTime(latest, timeAtDelete.minusMillis(1)).now()).isNotNull();
    assertThat(loadAtPointInTime(latest, timeAtDelete).now()).isNull();
    assertThat(loadAtPointInTime(latest, timeAtDelete.plusMillis(1)).now()).isNull();
  }

  @Test
  public void testLoadAtPointInTime_beforeCreated_returnsNull() throws Exception {
    clock.advanceOneMilli();
    // Don't save a commit log, we shouldn't need one.
    HostResource host = persistResource(
        newHostResource("ns1.cat.tld").asBuilder()
            .setCreationTime(clock.nowUtc())
            .build());
    assertThat(loadAtPointInTime(host, clock.nowUtc().minus(1)).now()).isNull();
  }

  @Test
  public void testLoadAtPointInTime_atOrAfterLastAutoUpdateTime_returnsResource() throws Exception {
    clock.advanceOneMilli();
    // Don't save a commit log, we shouldn't need one.
    HostResource host = persistResource(
        newHostResource("ns1.cat.tld").asBuilder()
            .setCreationTime(START_OF_TIME)
            .build());
    assertThat(loadAtPointInTime(host, clock.nowUtc()).now()).isEqualTo(host);
  }

  @Test
  public void testLoadAtPointInTime_usingIntactRevisionHistory_returnsMutationValue()
      throws Exception {
    clock.advanceOneMilli();
    // Save resource with a commit log that we can read in later as a revisions map value.
    HostResource oldHost = persistResourceWithCommitLog(
        newHostResource("ns1.cat.tld").asBuilder()
            .setCreationTime(START_OF_TIME)
            .setCurrentSponsorClientId("OLD")
            .build());
    // Advance a day so that the next created revision entry doesn't overwrite the existing one.
    clock.advanceBy(Duration.standardDays(1));
    // Overwrite the current host with one that has different data.
    HostResource currentHost = persistResource(oldHost.asBuilder()
            .setCurrentSponsorClientId("NEW")
            .build());
    // Load at the point in time just before the latest update; the floor entry of the revisions
    // map should point to the manifest for the first save, so we should get the old host.
    assertThat(loadAtPointInTime(currentHost, clock.nowUtc().minusMillis(1)).now())
        .isEqualTo(oldHost);
  }

  @Test
  public void testLoadAtPointInTime_brokenRevisionHistory_returnsResourceAsIs()
      throws Exception {
    // Don't save a commit log, we want to test the handling of a broken revisions reference.
    HostResource oldHost = persistResource(
        newHostResource("ns1.cat.tld").asBuilder()
            .setCreationTime(START_OF_TIME)
            .setCurrentSponsorClientId("OLD")
            .build());
    // Advance a day so that the next created revision entry doesn't overwrite the existing one.
    clock.advanceBy(Duration.standardDays(1));
    // Overwrite the existing resource to force revisions map use.
    HostResource host = persistResource(oldHost.asBuilder()
        .setCurrentSponsorClientId("NEW")
        .build());
    // Load at the point in time just before the latest update; the old host is not recoverable
    // (revisions map link is broken, and guessing using the oldest revision map entry finds the
    // same broken link), so just returns the current host.
    assertThat(loadAtPointInTime(host, clock.nowUtc().minusMillis(1)).now()).isEqualTo(host);
  }

  @Test
  public void testLoadAtPointInTime_fallback_returnsMutationValueForOldestRevision()
      throws Exception {
    clock.advanceOneMilli();
    // Save a commit log that we can fall back to.
    HostResource oldHost = persistResourceWithCommitLog(
        newHostResource("ns1.cat.tld").asBuilder()
            .setCreationTime(START_OF_TIME)
            .setCurrentSponsorClientId("OLD")
            .build());
    // Advance a day so that the next created revision entry doesn't overwrite the existing one.
    clock.advanceBy(Duration.standardDays(1));
    // Overwrite the current host with one that has different data.
    HostResource currentHost = persistResource(oldHost.asBuilder()
        .setCurrentSponsorClientId("NEW")
        .build());
    // Load at the point in time before the first update; there will be no floor entry for the
    // revisions map, so give up and return the oldest revision entry's mutation value (the old host
    // data).
    assertThat(loadAtPointInTime(currentHost, clock.nowUtc().minusDays(2)).now())
        .isEqualTo(oldHost);
  }

  @Test
  public void testLoadAtPointInTime_ultimateFallback_onlyOneRevision_returnsCurrentResource()
      throws Exception {
    clock.advanceOneMilli();
    // Don't save a commit log; we want to test that we load from the current resource.
    HostResource host = persistResource(
        newHostResource("ns1.cat.tld").asBuilder()
            .setCreationTime(START_OF_TIME)
            .setCurrentSponsorClientId("OLD")
            .build());
    // Load at the point in time before the first save; there will be no floor entry for the
    // revisions map.  Since the oldest revision entry is the only (i.e. current) revision, return
    // the resource.
    assertThat(loadAtPointInTime(host, clock.nowUtc().minusMillis(1)).now()).isEqualTo(host);
  }

  @Test
  public void testLoadAtPointInTime_moreThanThirtyDaysInPast_historyIsPurged() throws Exception {
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
          .now().getUpdateAutoTimestamp().getTimestamp())
              .isEqualTo(host.getRevisions().firstKey());
  }
}
