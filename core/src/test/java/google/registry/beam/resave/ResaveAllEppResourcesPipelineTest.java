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

package google.registry.beam.resave;

import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static com.google.common.truth.Truth.assertThat;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.loadAllOf;
import static google.registry.testing.DatabaseHelper.loadByEntity;
import static google.registry.testing.DatabaseHelper.persistActiveContact;
import static google.registry.testing.DatabaseHelper.persistActiveDomain;
import static google.registry.testing.DatabaseHelper.persistDomainWithDependentResources;
import static google.registry.testing.DatabaseHelper.persistDomainWithPendingTransfer;
import static google.registry.testing.DatabaseHelper.persistNewRegistrars;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import google.registry.beam.TestPipelineExtension;
import google.registry.model.EppResource;
import google.registry.model.contact.Contact;
import google.registry.model.domain.Domain;
import google.registry.model.domain.GracePeriod;
import google.registry.model.eppcommon.StatusValue;
import google.registry.persistence.PersistenceModule.TransactionIsolationLevel;
import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.persistence.transaction.JpaTestExtensions.JpaIntegrationTestExtension;
import google.registry.persistence.transaction.JpaTransactionManager;
import google.registry.persistence.transaction.TransactionManagerFactory;
import google.registry.testing.FakeClock;
import org.apache.beam.sdk.options.PipelineOptionsFactory;
import org.hibernate.cfg.Environment;
import org.joda.time.DateTime;
import org.joda.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockito.ArgumentCaptor;

/** Tests for {@link ResaveAllEppResourcesPipeline}. */
public class ResaveAllEppResourcesPipelineTest {

  private final FakeClock fakeClock = new FakeClock(DateTime.parse("2020-03-10T00:00:00.000Z"));

  @RegisterExtension
  final TestPipelineExtension testPipeline =
      TestPipelineExtension.create().enableAbandonedNodeEnforcement(true);

  @RegisterExtension
  final JpaIntegrationTestExtension database =
      new JpaTestExtensions.Builder()
          .withClock(fakeClock)
          .withProperty(
              Environment.ISOLATION, TransactionIsolationLevel.TRANSACTION_REPEATABLE_READ.name())
          .buildIntegrationTestExtension();

  private final ResaveAllEppResourcesPipelineOptions options =
      PipelineOptionsFactory.create().as(ResaveAllEppResourcesPipelineOptions.class);

  @BeforeEach
  void beforeEach() {
    options.setFast(true);
    persistNewRegistrars("TheRegistrar", "NewRegistrar");
    createTld("tld");
  }

  @Test
  void testPipeline_unchangedEntity() {
    Contact contact = persistActiveContact("test123");
    DateTime creationTime = contact.getUpdateTimestamp().getTimestamp();
    fakeClock.advanceOneMilli();
    assertThat(loadByEntity(contact).getUpdateTimestamp().getTimestamp()).isEqualTo(creationTime);
    fakeClock.advanceOneMilli();
    runPipeline();
    assertThat(loadByEntity(contact)).isEqualTo(contact);
  }

  @Test
  void testPipeline_fulfilledDomainTransfer() {
    options.setFast(true);
    DateTime now = fakeClock.nowUtc();
    Domain domain =
        persistDomainWithPendingTransfer(
            persistDomainWithDependentResources(
                "domain",
                "tld",
                persistActiveContact("jd1234"),
                now.minusDays(5),
                now.minusDays(5),
                now.plusYears(2)),
            now.minusDays(4),
            now.minusDays(1),
            now.plusYears(2));
    assertThat(domain.getStatusValues()).contains(StatusValue.PENDING_TRANSFER);
    assertThat(domain.getUpdateTimestamp().getTimestamp()).isEqualTo(now);
    fakeClock.advanceOneMilli();
    runPipeline();
    Domain postPipeline = loadByEntity(domain);
    assertThat(postPipeline.getStatusValues()).doesNotContain(StatusValue.PENDING_TRANSFER);
    assertThat(postPipeline.getUpdateTimestamp().getTimestamp()).isEqualTo(fakeClock.nowUtc());
  }

  @Test
  void testPipeline_autorenewedDomain() {
    DateTime now = fakeClock.nowUtc();
    Domain domain =
        persistDomainWithDependentResources(
            "domain", "tld", persistActiveContact("jd1234"), now, now, now.plusYears(1));
    assertThat(domain.getRegistrationExpirationTime()).isEqualTo(now.plusYears(1));
    fakeClock.advanceBy(Duration.standardDays(500));
    runPipeline();
    Domain postPipeline = loadByEntity(domain);
    assertThat(postPipeline.getRegistrationExpirationTime()).isEqualTo(now.plusYears(2));
  }

  @Test
  void testPipeline_expiredGracePeriod() {
    DateTime now = fakeClock.nowUtc();
    persistDomainWithDependentResources(
        "domain", "tld", persistActiveContact("jd1234"), now, now, now.plusYears(1));
    assertThat(loadAllOf(GracePeriod.class)).hasSize(1);
    fakeClock.advanceBy(Duration.standardDays(500));
    runPipeline();
    assertThat(loadAllOf(GracePeriod.class)).isEmpty();
  }

  @Test
  void testPipeline_fastOnlySavesChanged() {
    DateTime now = fakeClock.nowUtc();
    Contact contact = persistActiveContact("jd1234");
    persistDomainWithDependentResources("renewed", "tld", contact, now, now, now.plusYears(1));
    persistActiveDomain("nonrenewed.tld", now, now.plusYears(20));
    // Spy the transaction manager so we can be sure we're only saving the renewed domain
    JpaTransactionManager spy = spy(tm());
    TransactionManagerFactory.setJpaTm(() -> spy);
    ArgumentCaptor<Domain> domainPutCaptor = ArgumentCaptor.forClass(Domain.class);
    runPipeline();
    // We should only be attempting to put the one changed domain into the DB
    verify(spy).put(domainPutCaptor.capture());
    assertThat(domainPutCaptor.getValue().getDomainName()).isEqualTo("renewed.tld");
  }

  @Test
  void testPipeline_notFastResavesAll() {
    options.setFast(false);
    DateTime now = fakeClock.nowUtc();
    Contact contact = persistActiveContact("jd1234");
    Domain renewed =
        persistDomainWithDependentResources("renewed", "tld", contact, now, now, now.plusYears(1));
    Domain nonRenewed =
        persistDomainWithDependentResources(
            "nonrenewed", "tld", contact, now, now, now.plusYears(20));
    // Spy the transaction manager so we can be sure we're attempting to save everything
    JpaTransactionManager spy = spy(tm());
    TransactionManagerFactory.setJpaTm(() -> spy);
    ArgumentCaptor<EppResource> eppResourcePutCaptor = ArgumentCaptor.forClass(EppResource.class);
    runPipeline();
    // We should be attempting to put both domains (and the contact) in, even the unchanged ones
    verify(spy, times(3)).put(eppResourcePutCaptor.capture());
    assertThat(
            eppResourcePutCaptor.getAllValues().stream()
                .map(EppResource::getRepoId)
                .collect(toImmutableSet()))
        .containsExactly(contact.getRepoId(), renewed.getRepoId(), nonRenewed.getRepoId());
  }

  private void runPipeline() {
    ResaveAllEppResourcesPipeline pipeline = new ResaveAllEppResourcesPipeline(options);
    pipeline.setupPipeline(testPipeline);
    testPipeline.run().waitUntilFinish();
  }
}
