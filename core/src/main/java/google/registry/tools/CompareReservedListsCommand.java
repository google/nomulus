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

import static com.google.common.collect.ImmutableSortedSet.toImmutableSortedSet;
import static google.registry.model.common.EntityGroupRoot.getCrossTldKey;
import static google.registry.model.ofy.ObjectifyService.ofy;
import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;

import com.beust.jcommander.Parameters;
import com.google.appengine.repackaged.com.google.common.collect.Streams;
import com.google.common.collect.ImmutableSet;
import google.registry.model.registry.label.ReservedList;
import google.registry.model.registry.label.ReservedList.ReservedListEntry;
import google.registry.model.registry.label.ReservedListDatastoreDao;
import google.registry.model.registry.label.ReservedListSqlDao;
import java.util.Comparator;
import java.util.Optional;
import java.util.stream.Collectors;

/** Command to compare all ReservedLists in Datastore to all ReservedLists in Cloud SQL. */
@Parameters(
    separators = " =",
    commandDescription = "Compare all the ReservedLists in Datastore to those in Cloud SQL.")
final class CompareReservedListsCommand implements CommandWithRemoteApi {

  @Override
  public void run() {
    ImmutableSet<ReservedList> datastoreLists =
        ImmutableSet.copyOf(
            ofy().load().type(ReservedList.class).ancestor(getCrossTldKey()).list());

    ImmutableSet<ReservedList> cloudSqlLists =
        jpaTm()
            .transact(
                () ->
                    jpaTm().loadAllOf(ReservedList.class).stream()
                        .map(ReservedList::getName)
                        .map(ReservedListSqlDao::getLatestRevision)
                        .filter(Optional::isPresent)
                        .map(Optional::get)
                        .collect(
                            toImmutableSortedSet(Comparator.comparing(ReservedList::getName))));

    int listsWithDiffs = 0;

    for (ReservedList datastoreList : datastoreLists) {
      Optional<ReservedList> sqlList =
          ReservedListSqlDao.getLatestRevision(datastoreList.getName());
      if (!sqlList.isPresent()) {
        listsWithDiffs++;
        System.out.printf(
            "ReservedList with name %s is present in Datastore, but not in Cloud SQL%n",
            datastoreList.getName());
      } else {
        String datastoreListString =
            Streams.stream(
                    ReservedListDatastoreDao.getLatestRevision(datastoreList.getName())
                        .get()
                        .getReservedListEntries()
                        .values())
                .sorted(Comparator.comparing(ReservedListEntry::getLabel))
                .map(ReservedListEntry::toString)
                .collect(Collectors.joining("\n"));

        String sqlListString =
            Streams.stream(
                    ReservedListSqlDao.getLatestRevision(datastoreList.getName())
                        .get()
                        .getReservedListEntries()
                        .values())
                .sorted(Comparator.comparing(ReservedListEntry::getLabel))
                .map(ReservedListEntry::toString)
                .collect(Collectors.joining("\n"));

        // This will only print out the name of the unequal list. GetReservedListCommand should be
        // used to determine what the actual differences are.
        if (!datastoreListString.equals(sqlListString)) {
          listsWithDiffs++;
          System.out.printf(
              "ReservedList with name %s has different entries in each database%n",
              datastoreList.getName());
        }
      }
    }

    for (ReservedList sqlList : cloudSqlLists) {
      Optional<ReservedList> datastoreList =
          ReservedListDatastoreDao.getLatestRevision(sqlList.getName());
      if (!datastoreList.isPresent()) {
        listsWithDiffs++;
        System.out.printf(
            "ReservedList with name %s is present in Cloud SQL, but not in Datastore%n",
            sqlList.getName());
      }
    }

    System.out.printf("Found %s unequal lists.%n", listsWithDiffs);
  }
}
