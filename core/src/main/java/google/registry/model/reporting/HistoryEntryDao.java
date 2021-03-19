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

package google.registry.model.reporting;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static google.registry.model.ofy.ObjectifyService.ofy;
import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.util.DateTimeUtils.END_OF_TIME;
import static google.registry.util.DateTimeUtils.START_OF_TIME;

import com.google.common.collect.Iterables;
import google.registry.model.EppResource;
import google.registry.model.contact.ContactHistory;
import google.registry.model.contact.ContactResource;
import google.registry.model.domain.DomainBase;
import google.registry.model.domain.DomainHistory;
import google.registry.model.host.HostHistory;
import google.registry.model.host.HostResource;
import google.registry.persistence.VKey;
import java.util.Comparator;
import org.joda.time.DateTime;

/**
 * Retrieves {@link HistoryEntry} descendants (e.g. {@link DomainHistory}).
 *
 * <p>This class is configured to retrieve either from Datastore or SQL, depending on which database
 * is currently considered the primary database.
 */
public class HistoryEntryDao {

  /** Loads all history objects in the times specified, including all types. */
  public static Iterable<? extends HistoryEntry> loadAllHistoryObjects(
      DateTime afterTime, DateTime beforeTime) {
    if (tm().isOfy()) {
      return ofy()
          .load()
          .type(HistoryEntry.class)
          .order("modificationTime")
          .filter("modificationTime >=", afterTime)
          .filter("modificationTime <=", beforeTime);
    } else {
      return jpaTm()
          .transact(
              () ->
                  Iterables.concat(
                      loadAllHistoryObjectsFromSql(ContactHistory.class, afterTime, beforeTime),
                      loadAllHistoryObjectsFromSql(DomainHistory.class, afterTime, beforeTime),
                      loadAllHistoryObjectsFromSql(HostHistory.class, afterTime, beforeTime)));
    }
  }

  /** Loads all history objects corresponding to the given {@link EppResource}. */
  public static Iterable<? extends HistoryEntry> loadHistoryObjectsForResource(
      VKey<? extends EppResource> parentKey) {
    return loadHistoryObjectsForResource(parentKey, START_OF_TIME, END_OF_TIME);
  }

  /** Loads all history objects in the time period specified for the given {@link EppResource}. */
  public static Iterable<? extends HistoryEntry> loadHistoryObjectsForResource(
      VKey<? extends EppResource> parentKey, DateTime afterTime, DateTime beforeTime) {
    if (tm().isOfy()) {
      return ofy()
          .load()
          .type(HistoryEntry.class)
          .ancestor(parentKey.getOfyKey())
          .order("modificationTime")
          .filter("modificationTime >=", afterTime)
          .filter("modificationTime <=", beforeTime);
    } else {
      return jpaTm()
          .transact(() -> loadHistoryObjectsForResourceFromSql(parentKey, afterTime, beforeTime));
    }
  }

  private static Iterable<? extends HistoryEntry> loadHistoryObjectsForResourceFromSql(
      VKey<? extends EppResource> parentKey, DateTime afterTime, DateTime beforeTime) {
    Class<? extends HistoryEntry> historyClass = getHistoryClassFromParent(parentKey.getKind());
    String repoIdFieldName = getRepoIdFieldNameFromHistoryClass(historyClass);
    String tableName = jpaTm().getEntityManager().getMetamodel().entity(historyClass).getName();
    String queryString =
        String.format(
            "SELECT entry FROM %s entry WHERE entry.modificationTime >= :afterTime AND "
                + "entry.modificationTime <= :beforeTime AND entry.%s = :parentKey",
            tableName, repoIdFieldName);
    return jpaTm()
        .query(queryString, historyClass)
        .setParameter("afterTime", afterTime)
        .setParameter("beforeTime", beforeTime)
        .setParameter("parentKey", parentKey.getSqlKey().toString())
        .getResultStream()
        .sorted(Comparator.comparing(HistoryEntry::getModificationTime))
        .collect(toImmutableList());
  }

  private static Class<? extends HistoryEntry> getHistoryClassFromParent(
      Class<? extends EppResource> parent) {
    if (parent.equals(ContactResource.class)) {
      return ContactHistory.class;
    } else if (parent.equals(DomainBase.class)) {
      return DomainHistory.class;
    } else if (parent.equals(HostResource.class)) {
      return HostHistory.class;
    }
    throw new IllegalArgumentException(
        String.format("Unknown history type for parent %s", parent.getName()));
  }

  private static String getRepoIdFieldNameFromHistoryClass(
      Class<? extends HistoryEntry> historyClass) {
    return historyClass.equals(ContactHistory.class)
        ? "contactRepoId"
        : historyClass.equals(DomainHistory.class) ? "domainRepoId" : "hostRepoId";
  }

  private static Iterable<? extends HistoryEntry> loadAllHistoryObjectsFromSql(
      Class<? extends HistoryEntry> historyClass, DateTime afterTime, DateTime beforeTime) {
    return jpaTm()
        .query(
            String.format(
                "SELECT entry FROM %s entry WHERE entry.modificationTime >= :afterTime AND "
                    + "entry.modificationTime <= :beforeTime",
                jpaTm().getEntityManager().getMetamodel().entity(historyClass).getName()),
            historyClass)
        .setParameter("afterTime", afterTime)
        .setParameter("beforeTime", beforeTime)
        .getResultList();
  }
}
