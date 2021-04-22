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

import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Streams;
import google.registry.model.EppResource;
import google.registry.model.contact.ContactHistory;
import google.registry.model.contact.ContactResource;
import google.registry.model.domain.DomainBase;
import google.registry.model.domain.DomainHistory;
import google.registry.model.host.HostHistory;
import google.registry.model.host.HostResource;
import google.registry.persistence.VKey;
import google.registry.persistence.transaction.CriteriaQueryBuilder;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import javax.persistence.criteria.CriteriaBuilder;
import javax.persistence.criteria.CriteriaQuery;
import org.joda.time.DateTime;

/**
 * Retrieves {@link HistoryEntry} descendants (e.g. {@link DomainHistory}).
 *
 * <p>This class is configured to retrieve either from Datastore or SQL, depending on which database
 * is currently considered the primary database.
 */
public class HistoryEntryDao {

  /** Loads all history objects in the times specified, including all types. */
  public static ImmutableList<? extends HistoryEntry> loadAllHistoryObjects(
      DateTime afterTime, DateTime beforeTime) {
    if (tm().isOfy()) {
      return Streams.stream(
              ofy()
                  .load()
                  .type(HistoryEntry.class)
                  .order("modificationTime")
                  .filter("modificationTime >=", afterTime)
                  .filter("modificationTime <=", beforeTime))
          .map(HistoryEntry::toChildHistoryEntity)
          .collect(toImmutableList());
    } else {
      return jpaTm()
          .transact(
              () ->
                  new ImmutableList.Builder<HistoryEntry>()
                      .addAll(
                          loadAllHistoryObjectsFromSql(ContactHistory.class, afterTime, beforeTime))
                      .addAll(
                          loadAllHistoryObjectsFromSql(DomainHistory.class, afterTime, beforeTime))
                      .addAll(
                          loadAllHistoryObjectsFromSql(HostHistory.class, afterTime, beforeTime))
                      .build());
    }
  }

  /** Loads all history objects corresponding to the given {@link EppResource}. */
  public static ImmutableList<? extends HistoryEntry> loadHistoryObjectsForResource(
      VKey<? extends EppResource> parentKey) {
    return loadHistoryObjectsForResource(parentKey, START_OF_TIME, END_OF_TIME);
  }

  /** Loads all history objects in the time period specified for the given {@link EppResource}. */
  public static ImmutableList<? extends HistoryEntry> loadHistoryObjectsForResource(
      VKey<? extends EppResource> parentKey, DateTime afterTime, DateTime beforeTime) {
    if (tm().isOfy()) {
      return Streams.stream(
              ofy()
                  .load()
                  .type(HistoryEntry.class)
                  .ancestor(parentKey.getOfyKey())
                  .order("modificationTime")
                  .filter("modificationTime >=", afterTime)
                  .filter("modificationTime <=", beforeTime))
          .map(HistoryEntry::toChildHistoryEntity)
          .collect(toImmutableList());
    } else {
      return jpaTm()
          .transact(() -> loadHistoryObjectsForResourceFromSql(parentKey, afterTime, beforeTime));
    }
  }

  /** Loads all history objects from all time from the given registrars. */
  public static Iterable<? extends HistoryEntry> loadHistoryObjectsByRegistrars(
      ImmutableCollection<String> registrarIds) {
    if (tm().isOfy()) {
      return ofy()
          .load()
          .type(HistoryEntry.class)
          .filter("clientId in", registrarIds)
          .order("modificationTime");
    } else {
      return jpaTm()
          .transact(
              () ->
                  Streams.concat(
                          loadHistoryObjectFromSqlByRegistrars(ContactHistory.class, registrarIds),
                          loadHistoryObjectFromSqlByRegistrars(DomainHistory.class, registrarIds),
                          loadHistoryObjectFromSqlByRegistrars(HostHistory.class, registrarIds))
                      .sorted(Comparator.comparing(HistoryEntry::getModificationTime))
                      .collect(toImmutableList()));
    }
  }

  private static Stream<? extends HistoryEntry> loadHistoryObjectFromSqlByRegistrars(
      Class<? extends HistoryEntry> historyClass, ImmutableCollection<String> registrarIds) {
    return jpaTm()
        .getEntityManager()
        .createQuery(
            CriteriaQueryBuilder.create(historyClass)
                .whereFieldIsIn("clientId", registrarIds)
                .build())
        .getResultStream();
  }

  private static ImmutableList<? extends HistoryEntry> loadHistoryObjectsForResourceFromSql(
      VKey<? extends EppResource> parentKey, DateTime afterTime, DateTime beforeTime) {
    // The class we're searching from is based on which parent type (e.g. Domain) we have
    Class<? extends HistoryEntry> historyClass = getHistoryClassFromParent(parentKey.getKind());
    // The field representing repo ID unfortunately varies by history class
    String repoIdFieldName = getRepoIdFieldNameFromHistoryClass(historyClass);
    CriteriaBuilder criteriaBuilder = jpaTm().getEntityManager().getCriteriaBuilder();
    CriteriaQuery<? extends HistoryEntry> criteriaQuery =
        CriteriaQueryBuilder.create(historyClass)
            .where("modificationTime", criteriaBuilder::greaterThanOrEqualTo, afterTime)
            .where("modificationTime", criteriaBuilder::lessThanOrEqualTo, beforeTime)
            .where(repoIdFieldName, criteriaBuilder::equal, parentKey.getSqlKey().toString())
            .build();

    return ImmutableList.sortedCopyOf(
        Comparator.comparing(HistoryEntry::getModificationTime),
        jpaTm().getEntityManager().createQuery(criteriaQuery).getResultList());
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

  private static List<? extends HistoryEntry> loadAllHistoryObjectsFromSql(
      Class<? extends HistoryEntry> historyClass, DateTime afterTime, DateTime beforeTime) {
    CriteriaBuilder criteriaBuilder = jpaTm().getEntityManager().getCriteriaBuilder();
    return jpaTm()
        .getEntityManager()
        .createQuery(
            CriteriaQueryBuilder.create(historyClass)
                .where("modificationTime", criteriaBuilder::greaterThanOrEqualTo, afterTime)
                .where("modificationTime", criteriaBuilder::lessThanOrEqualTo, beforeTime)
                .build())
        .getResultList();
  }
}
