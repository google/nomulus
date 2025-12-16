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
import google.registry.model.domain.Domain;
import google.registry.monitoring.whitebox.EppMetric;
import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.persistence.transaction.JpaTestExtensions.JpaIntegrationTestExtension;
import google.registry.testing.EppLoader;
import google.registry.testing.FakeClock;
import google.registry.testing.FakeHttpSession;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Test that we can reload EPP resources as they were in the past. */
class EppPointInTimeTest {

  private final FakeClock clock = new FakeClock(DateTime.now(UTC));

  @RegisterExtension
  final JpaIntegrationTestExtension jpa =
      new JpaTestExtensions.Builder().withClock(clock).buildIntegrationTestExtension();

  private EppLoader eppLoader;

  @BeforeEach
  void beforeEach() {
    createTld("tld");
  }

  private void runFlow() throws Exception {
    SessionMetadata sessionMetadata = new HttpSessionMetadata(new FakeHttpSession());
    sessionMetadata.setRegistrarId("TheRegistrar");
    DaggerEppTestComponent.builder()
        .fakesAndMocksModule(FakesAndMocksModule.create(clock))
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

  @Test
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
    Domain domainAfterCreate = Iterables.getOnlyElement(loadAllOf(Domain.class));
    assertThat(domainAfterCreate.getDomainName()).isEqualTo("example.tld");

    clock.advanceBy(standardDays(2));
    DateTime timeAtFirstUpdate = clock.nowUtc();
    eppLoader = new EppLoader(this, "domain_update_dsdata_add.xml");
    runFlow();

    Domain domainAfterFirstUpdate = loadByEntity(domainAfterCreate);
    assertThat(domainAfterCreate).isNotEqualTo(domainAfterFirstUpdate);

    clock.advanceOneMilli(); // same day as first update
    DateTime timeAtSecondUpdate = clock.nowUtc();
    eppLoader = new EppLoader(this, "domain_update_dsdata_rem.xml");
    runFlow();
    Domain domainAfterSecondUpdate = loadByEntity(domainAfterCreate);

    clock.advanceBy(standardDays(2));
    DateTime timeAtDelete = clock.nowUtc(); // before 'add' grace period ends
    eppLoader = new EppLoader(this, "domain_delete.xml", ImmutableMap.of("DOMAIN", "example.tld"));
    runFlow();

    assertThat(domainAfterFirstUpdate).isNotEqualTo(domainAfterSecondUpdate);

    // Point-in-time can only rewind an object from the current version, not roll forward.
    Domain latest = loadByEntity(domainAfterCreate);

    // Creation time has millisecond granularity due to isActive() check.
    assertThat(loadAtPointInTime(latest, timeAtCreate.minusMillis(1))).isNull();
    assertThat(loadAtPointInTime(latest, timeAtCreate)).isNotNull();
    assertThat(loadAtPointInTime(latest, timeAtCreate.plusMillis(1))).isNotNull();

    assertAboutImmutableObjects()
        .that(loadAtPointInTime(latest, timeAtCreate.plusDays(1)))
        .isEqualExceptFields(domainAfterCreate, "updateTimestamp");

    // In SQL, we are not limited by the day granularity, so when we request the object
    // at timeAtFirstUpdate we should receive the object at that first update, even though the
    // second update occurred one millisecond later.
    assertAboutImmutableObjects()
        .that(loadAtPointInTime(latest, timeAtFirstUpdate))
        .isEqualExceptFields(domainAfterFirstUpdate, "updateTimestamp");

    assertAboutImmutableObjects()
        .that(loadAtPointInTime(latest, timeAtSecondUpdate))
        .isEqualExceptFields(domainAfterSecondUpdate, "updateTimestamp");

    assertAboutImmutableObjects()
        .that(loadAtPointInTime(latest, timeAtSecondUpdate.plusDays(1)))
        .isEqualExceptFields(domainAfterSecondUpdate, "updateTimestamp");

    // Deletion time has millisecond granularity due to isActive() check.
    assertThat(loadAtPointInTime(latest, timeAtDelete.minusMillis(1))).isNotNull();
    assertThat(loadAtPointInTime(latest, timeAtDelete)).isNull();
    assertThat(loadAtPointInTime(latest, timeAtDelete.plusMillis(1))).isNull();
  }
}
