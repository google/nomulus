// Copyright 2021 The Nomulus Authors. All Rights Reserved.
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

package google.registry.model.bulkquery;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.model.bulkquery.BulkQueryHelper.loadAndAssembleDomain;
import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;
import static org.joda.time.DateTimeZone.UTC;

import com.google.common.collect.Sets;
import com.google.common.collect.Sets.SetView;
import com.google.common.truth.Truth8;
import google.registry.model.domain.Domain;
import google.registry.testing.AppEngineExtension;
import google.registry.testing.FakeClock;
import java.util.Set;
import java.util.stream.Collectors;
import javax.persistence.metamodel.Attribute;
import org.joda.time.DateTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for reading {@link DomainLite}. */
class DomainLiteTest {

  protected FakeClock fakeClock = new FakeClock(DateTime.now(UTC));

  @RegisterExtension
  public final AppEngineExtension appEngine =
      AppEngineExtension.builder().withCloudSql().withClock(fakeClock).build();

  private final TestSetupHelper setupHelper = new TestSetupHelper(fakeClock);

  @BeforeEach
  void setUp() {
    setupHelper.initializeAllEntities();
  }

  @AfterEach
  void afterEach() {
    setupHelper.tearDownBulkQueryJpaTm();
  }

  @Test
  void readDomainHost() {
    setupHelper.applyChangeToDomainAndHistory();
    setupHelper.setupBulkQueryJpaTm(appEngine);
    Truth8.assertThat(
            jpaTm().transact(() -> jpaTm().loadAllOf(DomainHost.class)).stream()
                .map(DomainHost::getHostVKey))
        .containsExactly(setupHelper.host.createVKey());
  }

  @Test
  void domainLiteAttributes_versusDomain() {
    Set<String> domainAttributes =
        jpaTm()
            .transact(
                () ->
                    jpaTm().getEntityManager().getMetamodel().entity(Domain.class).getAttributes())
            .stream()
            .map(Attribute::getName)
            .collect(Collectors.toSet());
    setupHelper.setupBulkQueryJpaTm(appEngine);
    Set<String> domainLiteAttributes =
        jpaTm()
            .transact(
                () ->
                    jpaTm()
                        .getEntityManager()
                        .getMetamodel()
                        .entity(DomainLite.class)
                        .getAttributes())
            .stream()
            .map(Attribute::getName)
            .collect(Collectors.toSet());

    assertThat(domainAttributes).containsAtLeastElementsIn(domainLiteAttributes);

    SetView<?> excludedFromDomain = Sets.difference(domainAttributes, domainLiteAttributes);
    assertThat(excludedFromDomain)
        .containsExactly("internalDelegationSignerData", "internalGracePeriods", "nsHosts");
  }

  @Test
  void readDomainLite_simple() {
    setupHelper.setupBulkQueryJpaTm(appEngine);
    assertThat(loadAndAssembleDomain(TestSetupHelper.DOMAIN_REPO_ID)).isEqualTo(setupHelper.domain);
  }

  @Test
  void readDomainLite_full() {
    setupHelper.applyChangeToDomainAndHistory();
    setupHelper.setupBulkQueryJpaTm(appEngine);
    assertThat(loadAndAssembleDomain(TestSetupHelper.DOMAIN_REPO_ID)).isEqualTo(setupHelper.domain);
  }
}
