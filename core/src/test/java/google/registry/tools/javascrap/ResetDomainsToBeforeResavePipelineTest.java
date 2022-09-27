// Copyright 2022 The Nomulus Authors. All Rights Reserved.
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

package google.registry.tools.javascrap;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.loadByEntity;
import static google.registry.testing.DatabaseHelper.persistActiveContact;
import static google.registry.testing.DatabaseHelper.persistDomainWithDependentResources;
import static google.registry.testing.DatabaseHelper.persistNewRegistrar;
import static google.registry.testing.DatabaseHelper.persistResource;

import google.registry.beam.TestPipelineExtension;
import google.registry.model.contact.Contact;
import google.registry.model.domain.Domain;
import google.registry.model.domain.DomainHistory;
import google.registry.model.eppcommon.StatusValue;
import google.registry.model.reporting.HistoryEntry;
import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.testing.DatastoreEntityExtension;
import google.registry.testing.FakeClock;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Tests for {@link ResetDomainsToBeforeResavePipeline}. */
public class ResetDomainsToBeforeResavePipelineTest {

  private final FakeClock fakeClock = new FakeClock(DateTime.parse("2022-09-01T00:00:00.000Z"));

  @RegisterExtension
  JpaTestExtensions.JpaIntegrationTestExtension jpaEextension =
      new JpaTestExtensions.Builder().withClock(fakeClock).buildIntegrationTestExtension();

  @RegisterExtension
  DatastoreEntityExtension datastoreEntityExtension =
      new DatastoreEntityExtension().allThreads(true);

  @RegisterExtension TestPipelineExtension pipeline = TestPipelineExtension.create();

  @BeforeEach
  void beforeEach() {
    persistNewRegistrar("TheRegistrar");
    persistNewRegistrar("NewRegistrar");
    createTld("tld");
  }

  @Test
  void testDomainReset_allCases() {
    Contact contact = persistActiveContact("contact1234");

    // Three domains persisted before the pipeline. One unmodified, one modified before the
    // pipeline, one modified during the pipeline
    Domain beforeNoMods =
        persistDomainWithDependentResources(
            "before-no-mods",
            "tld",
            contact,
            fakeClock.nowUtc(),
            fakeClock.nowUtc(),
            fakeClock.nowUtc().plusYears(2));
    Domain beforeWithMods =
        persistDomainWithDependentResources(
            "before-with-mods",
            "tld",
            contact,
            fakeClock.nowUtc(),
            fakeClock.nowUtc(),
            fakeClock.nowUtc().plusYears(2));
    Domain beforeModBefore =
        persistDomainWithDependentResources(
            "before-mod-before",
            "tld",
            contact,
            fakeClock.nowUtc(),
            fakeClock.nowUtc(),
            fakeClock.nowUtc().plusYears(2));

    // Modify one domain prior to the pipeline running
    fakeClock.setTo(DateTime.parse("2022-09-03T00:00:00.000Z"));
    beforeModBefore =
        persistResource(
            beforeModBefore
                .asBuilder()
                .addStatusValue(StatusValue.CLIENT_DELETE_PROHIBITED)
                .build());
    persistResource(createHistory(beforeModBefore));

    // Two domains persisted during the pipeline, one will be modified, the other won't be
    fakeClock.setTo(DateTime.parse("2022-09-06T00:00:00.000Z"));

    Domain duringNoMods =
        persistDomainWithDependentResources(
            "during-no-mods",
            "tld",
            contact,
            fakeClock.nowUtc(),
            fakeClock.nowUtc(),
            fakeClock.nowUtc().plusYears(2));
    Domain duringWithMods =
        persistDomainWithDependentResources(
            "during-with-mods",
            "tld",
            contact,
            fakeClock.nowUtc(),
            fakeClock.nowUtc(),
            fakeClock.nowUtc().plusYears(2));

    // Modify two domains created beforehand
    beforeWithMods =
        persistResource(
            beforeWithMods
                .asBuilder()
                .addStatusValue(StatusValue.CLIENT_DELETE_PROHIBITED)
                .build());
    persistResource(createHistory(beforeWithMods));

    beforeModBefore =
        persistResource(
            beforeModBefore
                .asBuilder()
                .addStatusValue(StatusValue.CLIENT_RENEW_PROHIBITED)
                .build());
    persistResource(createHistory(beforeModBefore));

    // Modify one of the domains created during
    fakeClock.setTo(DateTime.parse("2022-09-08T00:00:00.000Z"));
    duringWithMods =
        persistResource(
            duringWithMods
                .asBuilder()
                .addStatusValue(StatusValue.CLIENT_DELETE_PROHIBITED)
                .build());
    persistResource(createHistory(duringWithMods));

    // After the pipeline, create one domain and modify the two that were modified before
    fakeClock.setTo(DateTime.parse("2022-09-12T00:00:00.000Z"));

    Domain afterNoMods =
        persistDomainWithDependentResources(
            "after-no-mods",
            "tld",
            contact,
            fakeClock.nowUtc(),
            fakeClock.nowUtc(),
            fakeClock.nowUtc().plusYears(2));

    beforeWithMods =
        persistResource(
            beforeWithMods.asBuilder().addStatusValue(StatusValue.CLIENT_RENEW_PROHIBITED).build());
    persistResource(createHistory(beforeWithMods));

    duringWithMods =
        persistResource(
            duringWithMods.asBuilder().addStatusValue(StatusValue.CLIENT_RENEW_PROHIBITED).build());
    persistResource(createHistory(duringWithMods));

    fakeClock.setTo(DateTime.parse("2022-09-20T00:00:00.000Z"));
    ResetDomainsToBeforeResavePipeline.setup(pipeline);
    pipeline.run().waitUntilFinish();

    // The results should be:
    // 1. beforeNoMods: unmodified
    // 2. beforeModBefore: should be the modified state before the pipeline
    // 3. beforeWithMods: should be the originally-persisted domain
    // 4. duringNoMods: unmodified
    // 5. duringWithMods: should be the originally-persisted domain
    // 6. afterMoMods: unmodified
    // This washes out to all status values being only INACTIVE except for the domain
    // (beforeModBefore) that had CLIENT_DELETE_PROHIBITED added before the pipeline
    assertOnlyInactive(beforeNoMods, beforeWithMods, duringNoMods, duringWithMods, afterNoMods);
    assertThat(loadByEntity(beforeModBefore).getStatusValues())
        .containsExactly(StatusValue.INACTIVE, StatusValue.CLIENT_DELETE_PROHIBITED);
  }

  private void assertOnlyInactive(Domain... domains) {
    for (Domain domain : domains) {
      assertThat(loadByEntity(domain).getStatusValues()).containsExactly(StatusValue.INACTIVE);
    }
  }

  private DomainHistory createHistory(Domain domain) {
    return new DomainHistory.Builder()
        .setDomain(domain)
        .setType(HistoryEntry.Type.DOMAIN_UPDATE)
        .setModificationTime(fakeClock.nowUtc())
        .setRegistrarId(domain.getCurrentSponsorRegistrarId())
        .build();
  }
}
