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
import static google.registry.testing.DatabaseHelper.persistActiveDomain;
import static google.registry.testing.DatabaseHelper.persistActiveHost;
import static google.registry.testing.DatabaseHelper.persistDomainWithDependentResources;
import static google.registry.testing.DatabaseHelper.persistDomainWithPendingTransfer;
import static google.registry.testing.DatabaseHelper.persistNewRegistrars;
import static google.registry.util.DateTimeUtils.minusDays;
import static google.registry.util.DateTimeUtils.plusYears;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import google.registry.beam.TestPipelineExtension;
import google.registry.model.EppResource;
import google.registry.model.domain.Domain;
import google.registry.model.domain.GracePeriod;
import google.registry.model.eppcommon.StatusValue;
import google.registry.model.host.Host;
import google.registry.persistence.PersistenceModule.TransactionIsolationLevel;
import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.persistence.transaction.JpaTestExtensions.JpaIntegrationTestExtension;
import google.registry.persistence.transaction.JpaTransactionManager;
import google.registry.persistence.transaction.TransactionManagerFactory;
import google.registry.testing.FakeClock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.apache.beam.sdk.options.PipelineOptionsFactory;
import org.hibernate.cfg.Environment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockito.ArgumentCaptor;

/** Tests for {@link ResaveAllEppResourcesPipeline}. */
public class ResaveAllEppResourcesPipelineTest {

  private final FakeClock fakeClock = new FakeClock(Instant.parse("2020-03-10T00:00:00.000Z"));

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
    Host host = persistActiveHost("ns1.example.tld");
    Instant creationTime = host.getUpdateTimestamp().getTimestamp();
    fakeClock.advanceOneMilli();
    assertThat(loadByEntity(host).getUpdateTimestamp().getTimestamp()).isEqualTo(creationTime);
    fakeClock.advanceOneMilli();
    runPipeline();
    assertThat(loadByEntity(host)).isEqualTo(host);
  }

  @Test
  void testPipeline_fulfilledDomainTransfer() {
    options.setFast(true);
    Instant now = fakeClock.now();
    Domain domain =
        persistDomainWithPendingTransfer(
            persistDomainWithDependentResources(
                "domain",
                "tld",
                now.minus(5, ChronoUnit.DAYS),
                now.minus(5, ChronoUnit.DAYS),
                plusYears(now, 2)),
            now.minus(4, ChronoUnit.DAYS),
            minusDays(now, 1),
            plusYears(now, 2));
    assertThat(domain.getStatusValues()).contains(StatusValue.PENDING_TRANSFER);
    assertThat(domain.getUpdateTimestamp().getTimestamp()).isEqualTo(now);
    fakeClock.advanceOneMilli();
    runPipeline();
    Domain postPipeline = loadByEntity(domain);
    assertThat(postPipeline.getStatusValues()).doesNotContain(StatusValue.PENDING_TRANSFER);
    assertThat(postPipeline.getUpdateTimestamp().getTimestamp()).isEqualTo(fakeClock.now());
  }

  @Test
  void testPipeline_autorenewedDomain() {
    Instant now = fakeClock.now();
    Domain domain =
        persistDomainWithDependentResources("domain", "tld", now, now, plusYears(now, 1));
    assertThat(domain.getRegistrationExpirationTime()).isEqualTo(plusYears(now, 1));
    fakeClock.advanceBy(Duration.ofDays(500));
    runPipeline();
    Domain postPipeline = loadByEntity(domain);
    assertThat(postPipeline.getRegistrationExpirationTime()).isEqualTo(plusYears(now, 2));
  }

  @Test
  void testPipeline_expiredGracePeriod() {
    Instant now = fakeClock.now();
    persistDomainWithDependentResources("domain", "tld", now, now, plusYears(now, 1));
    assertThat(loadAllOf(GracePeriod.class)).hasSize(1);
    fakeClock.advanceBy(Duration.ofDays(500));
    runPipeline();
    assertThat(loadAllOf(GracePeriod.class)).isEmpty();
  }

  @Test
  void testPipeline_fastOnlySavesChanged() {
    Instant now = fakeClock.now();
    persistDomainWithDependentResources("renewed", "tld", now, now, plusYears(now, 1));
    persistActiveDomain("nonrenewed.tld", now, plusYears(now, 20));
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
    Instant now = fakeClock.now();
    Domain renewed =
        persistDomainWithDependentResources("renewed", "tld", now, now, plusYears(now, 1));
    Domain nonRenewed =
        persistDomainWithDependentResources("nonrenewed", "tld", now, now, plusYears(now, 20));
    // Spy the transaction manager so we can be sure we're attempting to save everything
    JpaTransactionManager spy = spy(tm());
    TransactionManagerFactory.setJpaTm(() -> spy);
    ArgumentCaptor<EppResource> eppResourcePutCaptor = ArgumentCaptor.forClass(EppResource.class);
    runPipeline();
    // We should be attempting to put both domains in, even the unchanged one
    verify(spy, times(2)).put(eppResourcePutCaptor.capture());
    assertThat(
            eppResourcePutCaptor.getAllValues().stream()
                .map(EppResource::getRepoId)
                .collect(toImmutableSet()))
        .containsExactly(renewed.getRepoId(), nonRenewed.getRepoId());
  }

  private void runPipeline() {
    ResaveAllEppResourcesPipeline pipeline = new ResaveAllEppResourcesPipeline(options);
    pipeline.setupPipeline(testPipeline);
    testPipeline.run().waitUntilFinish();
  }
}
