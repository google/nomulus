// Copyright 2020 The Nomulus Authors. All Rights Reserved.
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
import static google.registry.model.ImmutableObjectSubject.immutableObjectCorrespondence;
import static google.registry.model.ofy.ObjectifyService.ofy;
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.newDomainBase;
import static google.registry.testing.DatabaseHelper.persistResource;

import com.google.common.collect.ImmutableList;
import com.google.common.truth.Correspondence;
import google.registry.model.ImmutableObject;
import google.registry.model.domain.DomainBase;
import google.registry.model.domain.GracePeriod;
import google.registry.model.domain.rgp.GracePeriodStatus;
import google.registry.testing.FakeClock;
import google.registry.testing.FakeResponse;
import google.registry.testing.mapreduce.MapreduceTestCase;
import java.util.Arrays;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit test for {@link ResaveAllDomainsAction}. */
public class ResaveAllDomainsActionTest extends MapreduceTestCase<ResaveAllDomainsAction> {
  private FakeClock fakeClock = new FakeClock();

  @BeforeEach
  void beforeEach() {
    action = new ResaveAllDomainsAction();
    action.mrRunner = makeDefaultRunner();
    action.response = new FakeResponse();
  }

  private void runMapreduce() throws Exception {
    action.run();
    executeTasksUntilEmpty("mapreduce");
  }

  @Test
  void test_mapreduceSuccessfullyResavesEntity() throws Exception {
    createTld("tld");
    DomainBase testDomain = newDomainBase("with-grace-period-id.tld");
    DomainBase domainWithGracePeriodId =
        persistResource(
            testDomain
                .asBuilder()
                .addGracePeriod(
                    GracePeriod.create(
                        GracePeriodStatus.ADD,
                        testDomain.getRepoId(),
                        fakeClock.nowUtc().plusDays(1),
                        "registrar",
                        null))
                .build());

    DomainBase anotherDomain = newDomainBase("with-grace-period-id.tld");
    DomainBase domainWithoutGracePeriodId =
        persistResource(
            anotherDomain
                .asBuilder()
                .addGracePeriod(
                    GracePeriod.create(
                            GracePeriodStatus.ADD,
                            anotherDomain.getRepoId(),
                            fakeClock.nowUtc().plusDays(1),
                            "registrar",
                            null)
                        .cloneWithNullId())
                .build());

    ofy().clearSessionCache();
    runMapreduce();

    assertThat(ImmutableList.of(loadDomain(domainWithGracePeriodId)))
        .comparingElementsUsing(getDomainCorrespondence())
        .containsExactly(domainWithGracePeriodId);

    DomainBase persisted = loadDomain(domainWithoutGracePeriodId);
    assertThat(ImmutableList.of(persisted))
        .comparingElementsUsing(getDomainCorrespondence("gracePeriods"))
        .containsExactly(domainWithoutGracePeriodId);
    assertThat(persisted.getGracePeriods())
        .comparingElementsUsing(immutableObjectCorrespondence("gracePeriodId"))
        .containsExactly(
            GracePeriod.create(
                    GracePeriodStatus.ADD,
                    anotherDomain.getRepoId(),
                    fakeClock.nowUtc().plusDays(1),
                    "registrar",
                    null)
                .cloneWithNullId());
    assertThat(persisted.getGracePeriods().iterator().next().getGracePeriodId()).isNotEqualTo(0L);

    // Execute the MapReduce job again to verify it dose not change existing gracePeriodId
    ofy().clearSessionCache();
    beforeEach();
    runMapreduce();

    assertThat(ImmutableList.of(loadDomain(domainWithGracePeriodId)))
        .comparingElementsUsing(getDomainCorrespondence())
        .containsExactly(domainWithGracePeriodId);
    assertThat(ImmutableList.of(loadDomain(domainWithoutGracePeriodId)))
        .comparingElementsUsing(getDomainCorrespondence())
        .containsExactly(persisted);
  }

  private static DomainBase loadDomain(DomainBase domain) {
    return ofy().load().key(domain.createVKey().getOfyKey()).now();
  }

  private static Correspondence<ImmutableObject, ImmutableObject> getDomainCorrespondence(
      String... ignoredFields) {
    return immutableObjectCorrespondence(
        Stream.concat(Stream.of("revisions", "updateTimestamp"), Arrays.stream(ignoredFields))
            .toArray(String[]::new));
  }
}
