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

package google.registry.tools;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableSortedSet.toImmutableSortedSet;
import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;

import com.beust.jcommander.Parameters;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Streams;
import google.registry.model.registry.label.PremiumList;
import google.registry.model.registry.label.PremiumList.PremiumListEntry;
import google.registry.model.registry.label.PremiumListDatastoreDao;
import google.registry.schema.tld.PremiumEntry;
import google.registry.schema.tld.PremiumListSqlDao;
import java.util.Comparator;
import java.util.Optional;
import java.util.stream.Collectors;
import org.hibernate.Hibernate;
import org.joda.money.BigMoney;

/** Command to compare all PremiumLists in Datastore to all PremiumLists in Cloud SQL. */
@Parameters(
    separators = " =",
    commandDescription = "Compare all the PremiumLists in Datastore to those in Cloud SQL.")
final class ComparePremiumListsCommand implements CommandWithRemoteApi {

  @Override
  public void run() {
    ImmutableSet<PremiumList> datastoreLists =
        tm().loadAllOf(PremiumList.class).stream()
            .map(PremiumList::getName)
            .map(PremiumListDatastoreDao::getLatestRevision)
            .filter(Optional::isPresent)
            .map(Optional::get)
            .peek(list -> Hibernate.initialize(list.getLabelsToPrices()))
            .collect(toImmutableSortedSet(Comparator.comparing(PremiumList::getName)));

    ImmutableSet<PremiumList> sqlLists =
        jpaTm()
            .transact(
                () ->
                    jpaTm().loadAllOf(PremiumList.class).stream()
                        .map(PremiumList::getName)
                        .map(PremiumListSqlDao::getLatestRevision)
                        .filter(Optional::isPresent)
                        .map(Optional::get)
                        .peek(list -> Hibernate.initialize(list.getLabelsToPrices()))
                        .collect(toImmutableSortedSet(Comparator.comparing(PremiumList::getName))));

    jpaTm()
        .transact(
            () -> {
              int listsWithDiffs = 0;

              for (PremiumList premiumList : datastoreLists) {
                Optional<PremiumList> sqlList =
                    PremiumListSqlDao.getLatestRevision(premiumList.getName());
                if (!sqlList.isPresent()) {
                  listsWithDiffs++;
                  System.out.printf(
                      "PremiumList with name %s is present in Datastore, but not in Cloud SQL.%n",
                      premiumList.getName());
                } else {

                  // Datastore and Cloud SQL use different objects to represent premium list entries
                  // so the best way to compare them is to compare their string representations.
                  String datastoreListString =
                      Streams.stream(
                              PremiumListDatastoreDao.loadPremiumListEntriesUncached(premiumList))
                          .sorted(Comparator.comparing(PremiumListEntry::getLabel))
                          .map(PremiumListEntry::toString)
                          .collect(Collectors.joining("\n"));

                  Iterable<PremiumEntry> sqlListEntries =
                      jpaTm()
                          .transact(
                              () ->
                                  PremiumListSqlDao.loadPremiumListEntriesUncached(sqlList.get()));

                  String sqlListString =
                      Streams.stream(
                              Streams.stream(sqlListEntries)
                                  .map(
                                      premiumEntry ->
                                          new PremiumListEntry.Builder()
                                              .setPrice(
                                                  BigMoney.of(
                                                          sqlList.get().getCurrency(),
                                                          premiumEntry.getPrice())
                                                      .toMoney())
                                              .setLabel(premiumEntry.getDomainLabel())
                                              .build())
                                  .collect(toImmutableList()))
                          .sorted(Comparator.comparing(PremiumListEntry::getLabel))
                          .map(PremiumListEntry::toString)
                          .collect(Collectors.joining("\n"));

                  // This will only print out the name of the unequal list. GetPremiumListCommand
                  // should be used to determine what the actual differences are.
                  if (!datastoreListString.equals(sqlListString)) {
                    listsWithDiffs++;
                    System.out.printf(
                        "PremiumList with name %s has different entries in each database.%n",
                        premiumList.getName());
                  }
                }
              }

              for (PremiumList sqlList : sqlLists) {
                Optional<PremiumList> datastoreList =
                    PremiumListDatastoreDao.getLatestRevision(sqlList.getName());
                if (!datastoreList.isPresent()) {
                  listsWithDiffs++;
                  System.out.printf(
                      "PremiumList with name %s is present in Cloud SQL, but not in Datastore.%n",
                      sqlList.getName());
                }
              }

              System.out.printf("Found %s unequal list(s).%n", listsWithDiffs);
            });
  }
}
