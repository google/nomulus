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
import static google.registry.model.ofy.ObjectifyService.ofy;
import static google.registry.testing.DatastoreHelper.createTld;
import static google.registry.testing.DatastoreHelper.persistActiveContact;
import static google.registry.testing.DatastoreHelper.persistActiveHost;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.joda.time.DateTimeZone.UTC;
import static org.joda.time.Duration.standardDays;

import com.google.common.collect.ImmutableMap;
import com.googlecode.objectify.Key;
import google.registry.flows.EppTestComponent.FakesAndMocksModule;
import google.registry.model.domain.DomainBase;
import google.registry.model.ofy.Ofy;
import google.registry.monitoring.whitebox.EppMetric;
import google.registry.testing.AppEngineRule;
import google.registry.testing.EppLoader;
import google.registry.testing.FakeClock;
import google.registry.testing.FakeHttpSession;
import google.registry.testing.InjectRule;
import org.joda.time.DateTime;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Test that domain flows create the commit logs needed to reload at points in the past. */
@RunWith(JUnit4.class)
public class EppCommitLogsTest {

  @Rule
  public final AppEngineRule appEngine =
      AppEngineRule.builder().withDatastoreAndCloudSql().withTaskQueue().build();

  @Rule
  public final InjectRule inject = new InjectRule();

  private final FakeClock clock = new FakeClock(DateTime.now(UTC));
  private EppLoader eppLoader;

  @Before
  public void init() {
    createTld("tld");
    inject.setStaticField(Ofy.class, "clock", clock);
  }

  private void runFlow() throws Exception {
    SessionMetadata sessionMetadata = new HttpSessionMetadata(new FakeHttpSession());
    sessionMetadata.setClientId("TheRegistrar");
    DaggerEppTestComponent.builder()
        .fakesAndMocksModule(
            FakesAndMocksModule.create(clock, EppMetric.builderForRequest(clock)))
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
  public void testLoadAtPointInTime() throws Exception {
    clock.setTo(DateTime.parse("1984-12-18T12:30Z"));  // not midnight

    persistActiveHost("ns1.example.net");
    persistActiveHost("ns2.example.net");
    persistActiveContact("jd1234");
    persistActiveContact("sh8013");

    clock.advanceBy(standardDays(1));
    DateTime timeAtCreate = clock.nowUtc();
    clock.setTo(timeAtCreate);
    eppLoader = new EppLoader(this, "domain_create.xml", ImmutableMap.of("DOMAIN", "example.tld"));
    runFlow();
    ofy().clearSessionCache();
    Key<DomainBase> key = Key.create(ofy().load().type(DomainBase.class).first().now());
    DomainBase domainAfterCreate = ofy().load().key(key).now();
    assertThat(domainAfterCreate.getDomainName()).isEqualTo("example.tld");

    clock.advanceBy(standardDays(2));
    DateTime timeAtFirstUpdate = clock.nowUtc();
    eppLoader = new EppLoader(this, "domain_update_dsdata_add.xml");
    runFlow();
    ofy().clearSessionCache();

    DomainBase domainAfterFirstUpdate = ofy().load().key(key).now();
    assertThat(domainAfterCreate).isNotEqualTo(domainAfterFirstUpdate);

    clock.advanceOneMilli();  // same day as first update
    DateTime timeAtSecondUpdate = clock.nowUtc();
    eppLoader = new EppLoader(this, "domain_update_dsdata_rem.xml");
    runFlow();
    ofy().clearSessionCache();
    DomainBase domainAfterSecondUpdate = ofy().load().key(key).now();

    clock.advanceBy(standardDays(2));
    DateTime timeAtDelete = clock.nowUtc(); // before 'add' grace period ends
    eppLoader = new EppLoader(this, "domain_delete.xml", ImmutableMap.of("DOMAIN", "example.tld"));
    runFlow();
    ofy().clearSessionCache();

    assertThat(domainAfterFirstUpdate).isNotEqualTo(domainAfterSecondUpdate);

    // Point-in-time can only rewind an object from the current version, not roll forward.
    DomainBase latest = ofy().load().key(key).now();

    // Creation time has millisecond granularity due to isActive() check.
    ofy().clearSessionCache();
    assertThat(loadAtPointInTime(latest, timeAtCreate.minusMillis(1)).now()).isNull();
    assertThat(loadAtPointInTime(latest, timeAtCreate).now()).isNotNull();
    assertThat(loadAtPointInTime(latest, timeAtCreate.plusMillis(1)).now()).isNotNull();

    ofy().clearSessionCache();
    assertThat(loadAtPointInTime(latest, timeAtCreate.plusDays(1)).now())
        .isEqualTo(domainAfterCreate);

    // Both updates happened on the same day. Since the revisions field has day granularity, the
    // key to the first update should have been overwritten by the second, and its timestamp rolled
    // forward. So we have to fall back to the last revision before midnight.
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
}
