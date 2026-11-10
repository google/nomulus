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
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static com.google.common.collect.ImmutableSortedSet.toImmutableSortedSet;
import static google.registry.bsa.persistence.Queries.getActivatedBsaTlds;
import static google.registry.bsa.persistence.Queries.getEnrolledBsaTlds;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static java.util.Comparator.naturalOrder;

import com.beust.jcommander.Parameters;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Sets;
import com.google.common.flogger.FluentLogger;
import google.registry.bsa.persistence.BsaTld;
import google.registry.model.tld.Tld;
import google.registry.tools.ConfirmingCommand;
import google.registry.util.Clock;
import jakarta.inject.Inject;
import java.time.Instant;

/**
 * One-time command to populate the {@link BsaTld} table with all enrolled BSA TLDs.
 *
 * <p>Activated TLDs that are not enrolled are flagged with a warning.
 */
@Parameters(
    separators = " =",
    commandDescription = "Populate the BsaTld table with all enrolled BSA TLDs.")
public class PopulateActivatedBsaTldsCommand extends ConfirmingCommand {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  @Inject Clock clock;

  private ImmutableSortedSet<String> tldsToAdd;
  private ImmutableSortedSet<String> activatedNotEnrolled;

  @Override
  protected void init() {
    tm().transact(
            () -> {
              ImmutableSet<String> enrolledTlds =
                  getEnrolledBsaTlds(clock.now()).map(Tld::getTldStr).collect(toImmutableSet());
              ImmutableSet<String> activatedTlds = getActivatedBsaTlds();
              activatedNotEnrolled =
                  Sets.difference(activatedTlds, enrolledTlds).stream()
                      .collect(toImmutableSortedSet(naturalOrder()));
              tldsToAdd =
                  Sets.difference(enrolledTlds, activatedTlds).stream()
                      .collect(toImmutableSortedSet(naturalOrder()));
            });
    if (!activatedNotEnrolled.isEmpty()) {
      logger.atWarning().log(
          "Activated TLD(s) that are not enrolled in BSA: %s", activatedNotEnrolled);
      printStream.printf(
          "Activated TLD(s) that are not enrolled in BSA: %s\n", activatedNotEnrolled);
    }
  }

  @Override
  protected String prompt() {
    return String.format("Add %d enrolled TLD(s) to BsaTld: %s?", tldsToAdd.size(), tldsToAdd);
  }

  @Override
  protected String execute() throws Exception {
    return tm().transact(
            () -> {
              ImmutableSet<String> currentlyActivated = getActivatedBsaTlds();
              ImmutableSortedSet<String> toAdd =
                  tldsToAdd.stream()
                      .filter(tld -> !currentlyActivated.contains(tld))
                      .collect(toImmutableSortedSet(naturalOrder()));
              if (toAdd.isEmpty()) {
                return "Added 0 TLD(s) to BsaTld: []";
              }
              Instant now = clock.now();
              ImmutableList<BsaTld> newBsaTlds =
                  toAdd.stream().map(tld -> new BsaTld(tld, now)).collect(toImmutableList());
              tm().putAll(newBsaTlds);
              return String.format("Added %d TLD(s) to BsaTld: %s", newBsaTlds.size(), toAdd);
            });
  }
}
