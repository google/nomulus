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

package google.registry.flows;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.model.EppResourceUtils.loadAtPointInTime;
import static google.registry.model.ImmutableObjectSubject.assertAboutImmutableObjects;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.loadAllOf;
import static google.registry.testing.DatabaseHelper.loadByEntity;
import static google.registry.testing.DatabaseHelper.persistActiveContact;
import static google.registry.testing.DatabaseHelper.persistActiveHost;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.joda.time.DateTimeZone.UTC;
import static org.joda.time.Duration.standardDays;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import google.registry.flows.EppTestComponent.FakesAndMocksModule;
import google.registry.model.domain.DomainBase;
import google.registry.model.ofy.Ofy;
import google.registry.monitoring.whitebox.EppMetric;
import google.registry.testing.AppEngineExtension;
import google.registry.testing.DualDatabaseTest;
import google.registry.testing.EppLoader;
import google.registry.testing.FakeClock;
import google.registry.testing.FakeHttpSession;
import google.registry.testing.InjectExtension;
import google.registry.testing.TestOfyAndSql;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Test that we can reload EPP resources as they were in the past. */
@DualDatabaseTest
class EppPointInTimeTest {

  private final FakeClock clock = new FakeClock(DateTime.now(UTC));

  @RegisterExtension
  final AppEngineExtension appEngine =
      AppEngineExtension.builder()
          .withDatastoreAndCloudSql()
          .withClock(clock)
          .withTaskQueue()
          .build();

  @RegisterExtension final InjectExtension inject = new InjectExtension();

  private EppLoader eppLoader;

  @BeforeEach
  void beforeEach() {
    createTld("tld");
    inject.setStaticField(Ofy.class, "clock", clock);
  }

  private void runFlow() throws Exception {
    SessionMetadata sessionMetadata = new HttpSessionMetadata(new FakeHttpSession());
    sessionMetadata.setClientId("TheRegistrar");
    DaggerEppTestComponent.builder()
        .fakesAndMocksModule(FakesAndMocksModule.create(clock, EppMetric.builderForRequest(clock)))
        .build()
        .startRequest()
        .flowComponentBuilder()
        .flowModule(
            new FlowModule.Builder()
                .setSessionMetadata(sessionMetadata)
                .setCredentials(new PasswordOnlyTransportCredentials())
                .setEppRequestSource(EppRequestSource.UNIT_TEST)
                .setIsDryRun(false)
                .setIsSuperuser(false)
                .setInputXmlBytes(eppLoader.getEppXml().getBytes(UTF_8))
                .setEppInput(eppLoader.getEpp())
                .build())
        .build()
        .flowRunner()
        .run(EppMetric.builder());
  }

  @TestOfyAndSql
  void testLoadAtPointInTime() throws Exception {
    clock.setTo(DateTime.parse("1984-12-18T12:30Z")); // not midnight

    persistActiveHost("ns1.example.net");
    persistActiveHost("ns2.example.net");
    persistActiveContact("jd1234");
    persistActiveContact("sh8013");

    clock.advanceBy(standardDays(1));
    DateTime timeAtCreate = clock.nowUtc();
    clock.setTo(timeAtCreate);
    eppLoader = new EppLoader(this, "domain_create.xml", ImmutableMap.of("DOMAIN", "example.tld"));
    runFlow();
    tm().clearSessionCache();
    DomainBase domainAfterCreate = Iterables.getOnlyElement(loadAllOf(DomainBase.class));
    assertThat(domainAfterCreate.getDomainName()).isEqualTo("example.tld");

    clock.advanceBy(standardDays(2));
    DateTime timeAtFirstUpdate = clock.nowUtc();
    eppLoader = new EppLoader(this, "domain_update_dsdata_add.xml");
    runFlow();
    tm().clearSessionCache();

    DomainBase domainAfterFirstUpdate = loadByEntity(domainAfterCreate);
    assertThat(domainAfterCreate).isNotEqualTo(domainAfterFirstUpdate);

    clock.advanceOneMilli(); // same day as first update
    DateTime timeAtSecondUpdate = clock.nowUtc();
    eppLoader = new EppLoader(this, "domain_update_dsdata_rem.xml");
    runFlow();
    tm().clearSessionCache();
    DomainBase domainAfterSecondUpdate = loadByEntity(domainAfterCreate);

    clock.advanceBy(standardDays(2));
    DateTime timeAtDelete = clock.nowUtc(); // before 'add' grace period ends
    eppLoader = new EppLoader(this, "domain_delete.xml", ImmutableMap.of("DOMAIN", "example.tld"));
    runFlow();
    tm().clearSessionCache();

    assertThat(domainAfterFirstUpdate).isNotEqualTo(domainAfterSecondUpdate);

    // Point-in-time can only rewind an object from the current version, not roll forward.
    DomainBase latest = loadByEntity(domainAfterCreate);

    // Creation time has millisecond granularity due to isActive() check.
    tm().clearSessionCache();
    assertThat(loadAtPointInTime(latest, timeAtCreate.minusMillis(1))).isNull();
    assertThat(loadAtPointInTime(latest, timeAtCreate)).isNotNull();
    assertThat(loadAtPointInTime(latest, timeAtCreate.plusMillis(1))).isNotNull();

    tm().clearSessionCache();
    assertAboutImmutableObjects()
        .that(loadAtPointInTime(latest, timeAtCreate.plusDays(1)))
        .isEqualExceptFields(domainAfterCreate, "updateTimestamp");

    tm().clearSessionCache();
    if (tm().isOfy()) {
      // Both updates happened on the same day. Since the revisions field has day granularity in
      // Datastore, the key to the first update should have been overwritten by the second, and its
      // timestamp rolled forward. So we have to fall back to the last revision before midnight.
      assertThat(loadAtPointInTime(latest, timeAtFirstUpdate)).isEqualTo(domainAfterCreate);
    } else {
      // In SQL, however, we are not limited by the day granularity, so when we request the object
      // at timeAtFirstUpdate we should receive the object at that first update, even though the
      // second update occurred one millisecond later.
      assertAboutImmutableObjects()
          .that(loadAtPointInTime(latest, timeAtFirstUpdate))
          .isEqualExceptFields(domainAfterFirstUpdate, "updateTimestamp");
    }

    tm().clearSessionCache();
    assertAboutImmutableObjects()
        .that(loadAtPointInTime(latest, timeAtSecondUpdate))
        .isEqualExceptFields(domainAfterSecondUpdate, "updateTimestamp");

    tm().clearSessionCache();
    assertAboutImmutableObjects()
        .that(loadAtPointInTime(latest, timeAtSecondUpdate.plusDays(1)))
        .isEqualExceptFields(domainAfterSecondUpdate, "updateTimestamp");

    // Deletion time has millisecond granularity due to isActive() check.
    tm().clearSessionCache();
    assertThat(loadAtPointInTime(latest, timeAtDelete.minusMillis(1))).isNotNull();
    assertThat(loadAtPointInTime(latest, timeAtDelete)).isNull();
    assertThat(loadAtPointInTime(latest, timeAtDelete.plusMillis(1))).isNull();
  }
}
