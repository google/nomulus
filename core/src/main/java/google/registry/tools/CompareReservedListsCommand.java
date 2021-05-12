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
import static google.registry.model.common.EntityGroupRoot.getCrossTldKey;
import static google.registry.model.ofy.ObjectifyService.auditedOfy;
import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;

import com.beust.jcommander.Parameters;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import google.registry.model.registry.label.ReservedList;
import google.registry.model.registry.label.ReservedList.ReservedListEntry;
import google.registry.model.registry.label.ReservedListDatastoreDao;
import google.registry.model.registry.label.ReservedListSqlDao;

/** Command to compare all ReservedLists in Datastore to all ReservedLists in Cloud SQL. */
@Parameters(
    separators = " =",
    commandDescription = "Compare all the ReservedLists in Datastore to those in Cloud SQL.")
final class CompareReservedListsCommand implements CommandWithRemoteApi {

  @Override
  public void run() {
    ImmutableSet<String> datastoreLists =
        auditedOfy().load().type(ReservedList.class).ancestor(getCrossTldKey()).list().stream()
            .map(ReservedList::getName)
            .collect(toImmutableSet());

    ImmutableSet<String> cloudSqlLists =
        jpaTm()
            .transact(
                () ->
                    jpaTm().loadAllOf(ReservedList.class).stream()
                        .map(ReservedList::getName)
                        .collect(toImmutableSet()));

    int listsWithDiffs = 0;

    for (String listName : Sets.difference(datastoreLists, cloudSqlLists)) {
      listsWithDiffs++;
      System.out.printf(
          "ReservedList '%s' is present in Datastore, but not in Cloud SQL.%n", listName);
    }
    for (String listName : Sets.difference(cloudSqlLists, datastoreLists)) {
      listsWithDiffs++;
      System.out.printf(
          "ReservedList '%s' is present in Cloud SQL, but not in Datastore.%n", listName);
    }

    for (String listName : Sets.intersection(datastoreLists, cloudSqlLists)) {
      ImmutableMap<String, ReservedListEntry> namesInSql =
          ReservedListSqlDao.getLatestRevision(listName).get().getReservedListEntries();

      ImmutableMap<String, ReservedListEntry> namesInDatastore =
          ReservedListDatastoreDao.getLatestRevision(listName).get().getReservedListEntries();

      // This will only print out the name of the unequal list. GetReservedListCommand should be
      // used to determine what the actual differences are.
      if (!namesInDatastore.equals(namesInSql)) {
        listsWithDiffs++;
        System.out.printf("ReservedList '%s' has different entries in each database.%n", listName);
      }
    }

    System.out.printf("Found %d unequal list(s).%n", listsWithDiffs);
  }
}
