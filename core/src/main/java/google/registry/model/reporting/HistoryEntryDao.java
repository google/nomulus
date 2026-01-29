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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.util.DateTimeUtils.END_OF_TIME;
import static google.registry.util.DateTimeUtils.START_OF_TIME;

import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Streams;
import google.registry.model.EppResource;
import google.registry.model.domain.Domain;
import google.registry.model.domain.DomainHistory;
import google.registry.model.host.Host;
import google.registry.model.host.HostHistory;
import google.registry.persistence.VKey;
import google.registry.persistence.transaction.CriteriaQueryBuilder;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.joda.time.DateTime;

/** Retrieves {@link HistoryEntry} descendants (e.g. {@link DomainHistory}). */
public class HistoryEntryDao {

  public static ImmutableMap<Class<? extends EppResource>, Class<? extends HistoryEntry>>
      RESOURCE_TYPES_TO_HISTORY_TYPES =
          ImmutableMap.of(
              Domain.class,
              DomainHistory.class,
              Host.class,
              HostHistory.class);

  /** Loads all history objects in the times specified, including all types. */
  public static ImmutableList<HistoryEntry> loadAllHistoryObjects(
      DateTime afterTime, DateTime beforeTime) {
    return tm().transact(
            () ->
                new ImmutableList.Builder<HistoryEntry>()
                    .addAll(loadAllHistoryObjects(DomainHistory.class, afterTime, beforeTime))
                    .addAll(loadAllHistoryObjects(HostHistory.class, afterTime, beforeTime))
                    .build());
  }

  /** Loads all history objects corresponding to the given {@link EppResource}. */
  public static ImmutableList<HistoryEntry> loadHistoryObjectsForResource(
      VKey<? extends EppResource> resourceKey) {
    return loadHistoryObjectsForResource(resourceKey, START_OF_TIME, END_OF_TIME);
  }

  /**
   * Loads all history objects corresponding to the given {@link EppResource} and cast to the
   * appropriate subclass.
   */
  public static <T extends HistoryEntry> ImmutableList<T> loadHistoryObjectsForResource(
      VKey<? extends EppResource> resourceKey, Class<T> subclazz) {
    return loadHistoryObjectsForResource(resourceKey, START_OF_TIME, END_OF_TIME, subclazz);
  }

  /** Loads all history objects in the time period specified for the given {@link EppResource}. */
  public static ImmutableList<HistoryEntry> loadHistoryObjectsForResource(
      VKey<? extends EppResource> resourceKey, DateTime afterTime, DateTime beforeTime) {
    return tm().transact(
            () -> loadHistoryObjectsForResourceInternal(resourceKey, afterTime, beforeTime));
  }

  /**
   * Loads all history objects in the time period specified for the given {@link EppResource} and
   * cast to the appropriate subclass.
   *
   * <p>Note that the subclass must be explicitly provided because we need compile time information
   * of T to return an {@code ImmutableList<T>}, even though at runtime we can call {@link
   * #getHistoryClassFromParent(Class)} to obtain it, which we also did to confirm that the provided
   * subclass is indeed correct.
   */
  private static <T extends HistoryEntry> ImmutableList<T> loadHistoryObjectsForResource(
      VKey<? extends EppResource> resourceKey,
      DateTime afterTime,
      DateTime beforeTime,
      Class<T> subclazz) {
    Class<? extends HistoryEntry> expectedSubclazz =
        getHistoryClassFromParent(resourceKey.getKind());
    checkArgument(
        subclazz.equals(expectedSubclazz),
        "The supplied HistoryEntry subclass %s is incompatible with the EppResource %s, "
            + "use %s instead",
        subclazz.getSimpleName(),
        resourceKey.getKind().getSimpleName(),
        expectedSubclazz.getSimpleName());
    return loadHistoryObjectsForResource(resourceKey, afterTime, beforeTime).stream()
        .map(subclazz::cast)
        .collect(toImmutableList());
  }

  /** Loads all history objects from all time from the given registrars. */
  public static Iterable<HistoryEntry> loadHistoryObjectsByRegistrars(
      ImmutableCollection<String> registrarIds) {
    return tm().reTransact(
            () ->
                Streams.concat(
                        loadHistoryObjectByRegistrarsInternal(DomainHistory.class, registrarIds),
                        loadHistoryObjectByRegistrarsInternal(HostHistory.class, registrarIds))
                    .sorted(Comparator.comparing(HistoryEntry::getModificationTime))
                    .collect(toImmutableList()));
  }

  private static <T extends HistoryEntry> Stream<T> loadHistoryObjectByRegistrarsInternal(
      Class<T> historyClass, ImmutableCollection<String> registrarIds) {
    return tm().criteriaQuery(
            CriteriaQueryBuilder.create(historyClass)
                .whereFieldIsIn("clientId", registrarIds)
                .build())
        .getResultStream();
  }

  private static ImmutableList<HistoryEntry> loadHistoryObjectsForResourceInternal(
      VKey<? extends EppResource> resourceKey, DateTime afterTime, DateTime beforeTime) {
    // The class we're searching from is based on which resource type (e.g. Domain) we have
    Class<? extends HistoryEntry> historyClass = getHistoryClassFromParent(resourceKey.getKind());
    CriteriaBuilder criteriaBuilder = tm().getEntityManager().getCriteriaBuilder();
    CriteriaQuery<? extends HistoryEntry> criteriaQuery =
        CriteriaQueryBuilder.create(historyClass)
            .where("modificationTime", criteriaBuilder::greaterThanOrEqualTo, afterTime)
            .where("modificationTime", criteriaBuilder::lessThanOrEqualTo, beforeTime)
            .where("repoId", criteriaBuilder::equal, resourceKey.getKey().toString())
            .orderByAsc("revisionId")
            .orderByAsc("modificationTime")
            .build();

    return ImmutableList.copyOf(tm().criteriaQuery(criteriaQuery).getResultList());
  }

  public static Class<? extends HistoryEntry> getHistoryClassFromParent(
      Class<? extends EppResource> resourceType) {
    if (!RESOURCE_TYPES_TO_HISTORY_TYPES.containsKey(resourceType)) {
      throw new IllegalArgumentException(
          String.format("Unknown history type for resourceType %s", resourceType.getName()));
    }
    return RESOURCE_TYPES_TO_HISTORY_TYPES.get(resourceType);
  }

  private static <T extends HistoryEntry> List<T> loadAllHistoryObjects(
      Class<T> historyClass, DateTime afterTime, DateTime beforeTime) {
    CriteriaBuilder criteriaBuilder = tm().getEntityManager().getCriteriaBuilder();
    return tm().criteriaQuery(
            CriteriaQueryBuilder.create(historyClass)
                .where("modificationTime", criteriaBuilder::greaterThanOrEqualTo, afterTime)
                .where("modificationTime", criteriaBuilder::lessThanOrEqualTo, beforeTime)
                .build())
        .getResultList();
  }
}
