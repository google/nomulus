// Copyright 2026 The Nomulus Authors. All Rights Reserved.
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

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.truth.Truth.assertThat;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.loadAllOf;
import static google.registry.testing.DatabaseHelper.persistResource;
import static google.registry.util.DateTimeUtils.START_INSTANT;

import com.google.common.collect.ImmutableList;
import google.registry.bsa.persistence.BsaTld;
import google.registry.bsa.persistence.Queries;
import google.registry.model.tld.Tld;
import google.registry.model.tld.Tld.TldType;
import google.registry.tools.CommandTestCase;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Tests for {@link PopulateActivatedBsaTldsCommand}. */
class PopulateActivatedBsaTldsCommandTest extends CommandTestCase<PopulateActivatedBsaTldsCommand> {

  private static final Instant NOW = Instant.parse("2024-05-01T00:00:00Z");

  @BeforeEach
  void beforeEach() {
    fakeClock.setTo(NOW);
    command.clock = fakeClock;
    command.printStream = System.out;
  }

  @Test
  void testSuccess_addsEnrolledTldsWithSameTimestamp() throws Exception {
    createEnrolledTld("app");
    createEnrolledTld("dev");
    createEnrolledTld("page");

    runCommandForced();

    assertInStdout("Add 3 enrolled TLD(s) to BsaTld: [app, dev, page]?");
    assertInStdout("Added 3 TLD(s) to BsaTld: [app, dev, page]");

    assertThat(tm().transact(Queries::getActivatedBsaTlds)).containsExactly("app", "dev", "page");

    ImmutableList<BsaTld> persisted = loadAllOf(BsaTld.class);
    assertThat(persisted).hasSize(3);
    assertThat(persisted.stream().map(BsaTld::getReadyTime).distinct().collect(toImmutableList()))
        .containsExactly(NOW);
  }

  @Test
  void testSuccess_doesNotReAddExistingTlds() throws Exception {
    createEnrolledTld("app");
    createEnrolledTld("dev");
    Instant earlierTime = NOW.minus(Duration.ofDays(10));
    tm().transact(() -> tm().put(new BsaTld("app", earlierTime)));

    runCommandForced();

    assertInStdout("Add 1 enrolled TLD(s) to BsaTld: [dev]?");
    assertInStdout("Added 1 TLD(s) to BsaTld: [dev]");

    assertThat(tm().transact(Queries::getActivatedBsaTlds)).containsExactly("app", "dev");

    ImmutableList<BsaTld> persisted = loadAllOf(BsaTld.class);
    BsaTld appTld = persisted.stream().filter(b -> b.getTld().equals("app")).findFirst().get();
    BsaTld devTld = persisted.stream().filter(b -> b.getTld().equals("dev")).findFirst().get();

    assertThat(appTld.getReadyTime()).isEqualTo(earlierTime);
    assertThat(devTld.getReadyTime()).isEqualTo(NOW);
  }

  @Test
  void testSuccess_flagsActivatedTldNotEnrolled() throws Exception {
    createEnrolledTld("app");
    tm().transact(() -> tm().put(new BsaTld("unEnrolled", NOW)));

    runCommandForced();

    assertInStdout("Activated TLD(s) that are not enrolled in BSA: [unEnrolled]");
    assertInStdout("Add 1 enrolled TLD(s) to BsaTld: [app]?");
    assertInStdout("Added 1 TLD(s) to BsaTld: [app]");

    assertThat(tm().transact(Queries::getActivatedBsaTlds)).containsExactly("unEnrolled", "app");
  }

  @Test
  void testSuccess_noTldsToAdd() throws Exception {
    createEnrolledTld("app");
    tm().transact(() -> tm().put(new BsaTld("app", NOW)));

    runCommandForced();

    assertInStdout("Add 0 enrolled TLD(s) to BsaTld: []?");
    assertInStdout("Added 0 TLD(s) to BsaTld: []");
    assertThat(tm().transact(Queries::getActivatedBsaTlds)).containsExactly("app");
  }

  @Test
  void testSuccess_ignoresNonRealTlds() throws Exception {
    createTld("testtld");
    persistResource(
        Tld.get("testtld")
            .asBuilder()
            .setTldType(TldType.TEST)
            .setBsaEnrollStartTime(Optional.of(START_INSTANT))
            .build());

    runCommandForced();

    assertInStdout("Add 0 enrolled TLD(s) to BsaTld: []?");
    assertThat(tm().transact(Queries::getActivatedBsaTlds)).isEmpty();
  }

  @Test
  void testSuccess_ignoresFutureEnrolledTlds() throws Exception {
    createTld("futuretld");
    persistResource(
        Tld.get("futuretld")
            .asBuilder()
            .setBsaEnrollStartTime(Optional.of(NOW.plus(Duration.ofDays(1))))
            .build());

    runCommandForced();

    assertInStdout("Add 0 enrolled TLD(s) to BsaTld: []?");
    assertThat(tm().transact(Queries::getActivatedBsaTlds)).isEmpty();
  }

  private static void createEnrolledTld(String tldStr) {
    createTld(tldStr);
    persistResource(
        Tld.get(tldStr).asBuilder().setBsaEnrollStartTime(Optional.of(START_INSTANT)).build());
  }
}
