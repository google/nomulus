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

import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;
import static google.registry.persistence.transaction.TransactionManagerFactory.ofyTm;

import com.beust.jcommander.Parameters;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.google.common.collect.Streams;
import google.registry.model.registry.label.PremiumList;
import google.registry.model.registry.label.PremiumList.PremiumListEntry;
import google.registry.model.registry.label.PremiumListDatastoreDao;
import google.registry.schema.tld.PremiumEntry;
import google.registry.schema.tld.PremiumListSqlDao;
import java.util.Optional;
import org.joda.money.BigMoney;

/** Command to compare all PremiumLists in Datastore to all PremiumLists in Cloud SQL. */
@Parameters(
    separators = " =",
    commandDescription = "Compare all the PremiumLists in Datastore to those in Cloud SQL.")
final class ComparePremiumListsCommand implements CommandWithRemoteApi {

  @Override
  public void run() {
    ImmutableSet<String> datastoreLists =
        ofyTm().loadAllOf(PremiumList.class).stream()
            .map(PremiumList::getName)
            .collect(toImmutableSet());

    ImmutableSet<String> sqlLists =
        jpaTm()
            .transact(
                () ->
                    jpaTm().loadAllOf(PremiumList.class).stream()
                        .map(PremiumList::getName)
                        .collect(toImmutableSet()));

    int listsWithDiffs = 0;

    for (String listName : Sets.difference(datastoreLists, sqlLists)) {
      listsWithDiffs++;
      System.out.printf(
          "PremiumList '%s' is present in Datastore, but not in Cloud SQL.%n", listName);
    }
    for (String listName : Sets.difference(sqlLists, datastoreLists)) {
      listsWithDiffs++;
      System.out.printf(
          "PremiumList '%s' is present in Cloud SQL, but not in Datastore.%n", listName);
    }

    for (String listName : Sets.intersection(datastoreLists, sqlLists)) {
      Optional<PremiumList> sqlList = PremiumListSqlDao.getLatestRevision(listName);

      // Datastore and Cloud SQL use different objects to represent premium list entries
      // so the best way to compare them is to compare their string representations.
      ImmutableSet<String> datastoreListStrings =
          Streams.stream(
                  PremiumListDatastoreDao.loadPremiumListEntriesUncached(
                      PremiumListDatastoreDao.getLatestRevision(listName).get()))
              .map(PremiumListEntry::toString)
              .collect(toImmutableSet());

      Iterable<PremiumEntry> sqlListEntries =
          jpaTm().transact(() -> PremiumListSqlDao.loadPremiumListEntriesUncached(sqlList.get()));

      ImmutableSet<String> sqlListStrings =
          Streams.stream(sqlListEntries)
              .map(
                  premiumEntry ->
                      new PremiumListEntry.Builder()
                          .setPrice(
                              BigMoney.of(sqlList.get().getCurrency(), premiumEntry.getPrice())
                                  .toMoney())
                          .setLabel(premiumEntry.getDomainLabel())
                          .build()
                          .toString())
              .collect(toImmutableSet());

      // This will only print out the name of the unequal list. GetPremiumListCommand
      // should be used to determine what the actual differences are.
      if (!datastoreListStrings.equals(sqlListStrings)) {
        listsWithDiffs++;
        System.out.printf("PremiumList '%s' has different entries in each database.%n", listName);
      }
    }

    System.out.printf("Found %d unequal list(s).%n", listsWithDiffs);
  }
}
