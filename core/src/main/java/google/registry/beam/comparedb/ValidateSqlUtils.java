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

package google.registry.beam.comparedb;

import static com.google.common.base.Verify.verify;
import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.flogger.FluentLogger;
import google.registry.beam.initsql.Transforms;
import google.registry.config.RegistryEnvironment;
import google.registry.model.BackupGroupRoot;
import google.registry.model.EppResource;
import google.registry.model.ImmutableObject;
import google.registry.model.annotations.DeleteAfterMigration;
import google.registry.model.billing.BillingEvent;
import google.registry.model.contact.ContactBase;
import google.registry.model.contact.ContactHistory;
import google.registry.model.domain.DomainContent;
import google.registry.model.domain.DomainHistory;
import google.registry.model.eppcommon.AuthInfo;
import google.registry.model.host.HostHistory;
import google.registry.model.poll.PollMessage;
import google.registry.model.registrar.Registrar;
import google.registry.model.replay.SqlEntity;
import google.registry.model.reporting.HistoryEntry;
import java.lang.reflect.Field;
import java.math.BigInteger;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.apache.beam.sdk.metrics.Counter;
import org.apache.beam.sdk.metrics.Metrics;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.values.KV;
import org.apache.beam.sdk.values.TupleTag;

/** Helpers for use by {@link ValidateSqlPipeline}. */
@DeleteAfterMigration
final class ValidateSqlUtils {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private ValidateSqlUtils() {}

  private static final ImmutableSet<String> PROBER_CELLS = ImmutableSet.of("IQ", "LG", "TL");
  private static final ImmutableSet<String> PROBER_TYPES =
      ImmutableSet.of("ANYT", "ANYTES", "CANARY");

  /**
   * Query template for finding the median value of the {@code history_revision_id} column in one of
   * the History tables.
   *
   * <p>The {@link ValidateSqlPipeline} uses this query to parallelize the query to some of the
   * history tables. Although the {@code repo_id} column is the leading column in the primary keys
   * of these tables, in practice and with production data, division by {@code history_revision_id}
   * works slightly faster for unknown reasons.
   */
  private static final String MEDIAN_ID_QUERY_TEMPLATE =
      "SELECT history_revision_id FROM (                                                        "
          + "  SELECT"
          + "    ROW_NUMBER() OVER (ORDER BY history_revision_id ASC) AS rownumber,"
          + "    history_revision_id"
          + "  FROM \"%TABLE%\""
          + ") AS foo\n"
          + "WHERE rownumber in (select count(*) / 2 + 1 from \"%TABLE%\")";

  static Optional<Long> getMedianIdForHistoryTable(String tableName) {
    Preconditions.checkArgument(
        tableName.endsWith("History"), "Table must be one of the History tables.");
    String sqlText = MEDIAN_ID_QUERY_TEMPLATE.replace("%TABLE%", tableName);
    List results =
        jpaTm()
            .transact(() -> jpaTm().getEntityManager().createNativeQuery(sqlText).getResultList());
    verify(results.size() < 2, "MidPoint query should have at most one result.");
    if (results.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(((BigInteger) results.get(0)).longValue());
  }

  static TupleTag<SqlEntity> createSqlEntityTupleTag(Class<? extends SqlEntity> actualType) {
    return new TupleTag<SqlEntity>(actualType.getSimpleName()) {};
  }

  static class CompareSqlEntity extends DoFn<KV<String, Iterable<SqlEntity>>, Void> {
    private final HashMap<String, Counter> totalCounters = new HashMap<>();
    private final HashMap<String, Counter> missingCounters = new HashMap<>();
    private final HashMap<String, Counter> unequalCounters = new HashMap<>();
    private final HashMap<String, Counter> badEntityCounters = new HashMap<>();
    private final HashMap<String, Counter> duplicateEntityCounters = new HashMap<>();

    private volatile boolean logPrinted = false;

    private String getCounterKey(Class<?> clazz) {
      return PollMessage.class.isAssignableFrom(clazz) ? "PollMessage" : clazz.getSimpleName();
    }

    private synchronized void ensureCounterExists(String counterKey) {
      if (totalCounters.containsKey(counterKey)) {
        return;
      }
      totalCounters.put(counterKey, Metrics.counter("CompareDB", "Total Compared: " + counterKey));
      missingCounters.put(
          counterKey, Metrics.counter("CompareDB", "Missing In One DB: " + counterKey));
      unequalCounters.put(counterKey, Metrics.counter("CompareDB", "Not Equal:" + counterKey));
      badEntityCounters.put(counterKey, Metrics.counter("CompareDB", "Bad Entities:" + counterKey));
      duplicateEntityCounters.put(
          counterKey, Metrics.counter("CompareDB", "Duplicate Entities:" + counterKey));
    }

    /**
     * A rudimentary debugging helper that prints the first pair of unequal entities in each worker.
     * This will be removed when we start exporting such entities to GCS.
     */
    void logDiff(String key, Object entry0, Object entry1) {
      if (logPrinted) {
        return;
      }
      logPrinted = true;
      Map<String, Object> fields0 = ((ImmutableObject) entry0).toDiffableFieldMap();
      Map<String, Object> fields1 = ((ImmutableObject) entry1).toDiffableFieldMap();
      StringBuilder sb = new StringBuilder();
      fields0.forEach(
          (field, value) -> {
            if (fields1.containsKey(field)) {
              if (!Objects.equals(value, fields1.get(field))) {
                sb.append(field + " not match: " + value + " -> " + fields1.get(field) + "\n");
              }
            } else {
              sb.append(field + "Not found in entity 2\n");
            }
          });
      fields1.forEach(
          (field, value) -> {
            if (!fields0.containsKey(field)) {
              sb.append(field + "Not found in entity 1\n");
            }
          });
      logger.atWarning().log(key + "  " + sb.toString());
    }

    @ProcessElement
    public void processElement(@Element KV<String, Iterable<SqlEntity>> kv) {
      ImmutableList<SqlEntity> entities = ImmutableList.copyOf(kv.getValue());

      verify(!entities.isEmpty(), "Can't happen: no value for key %s.", kv.getKey());

      String counterKey = getCounterKey(entities.get(0).getClass());
      ensureCounterExists(counterKey);
      totalCounters.get(counterKey).inc();

      if (entities.size() > 2) {
        // Duplicates may happen with Cursors if imported across projects. Its key in Datastore, the
        // id field, encodes the project name and is not fixed by the importing job.
        duplicateEntityCounters.get(counterKey).inc();
        return;
      }

      if (entities.size() == 1) {
        if (isSpecialCaseProberEntity(entities.get(0))) {
          return;
        }
        missingCounters.get(counterKey).inc();
        // Temporary debugging help. See logDiff() above.
        if (!logPrinted) {
          logPrinted = true;
          logger.atWarning().log("Unexpected single entity: %s", kv.getKey());
        }
        return;
      }
      SqlEntity entity0;
      SqlEntity entity1;

      try {
        entity0 = normalizeEntity(entities.get(0));
        entity1 = normalizeEntity(entities.get(1));
      } catch (Exception e) {
        // Temporary debugging help. See logDiff() above.
        if (!logPrinted) {
          logPrinted = true;
          badEntityCounters.get(counterKey).inc();
        }
        return;
      }

      if (!Objects.equals(entity0, entity1)) {
        unequalCounters.get(counterKey).inc();
        logDiff(kv.getKey(), entities.get(0), entities.get(1));
      }
    }
  }

  static SqlEntity normalizeEntity(SqlEntity sqlEntity) {
    if (sqlEntity instanceof EppResource) {
      return normalizeEppResource(sqlEntity);
    }
    if (sqlEntity instanceof HistoryEntry) {
      return (SqlEntity) normalizeHistoryEntry((HistoryEntry) sqlEntity);
    }
    return sqlEntity;
  }

  /**
   * Normalizes an {@link EppResource} instance for comparison.
   *
   * <p>This method may modify the input object using reflection instead of making a copy with
   * {@code eppResource.asBuilder().build()}, because when {@code eppResource} is a {@link
   * google.registry.model.domain.DomainBase}, the {@code build} method accesses the Database, which
   * we want to avoid.
   */
  static SqlEntity normalizeEppResource(SqlEntity eppResource) {
    try {
      if (isSpecialCaseProberEntity(eppResource)) {
        // Clearing some timestamps. See isSpecialCaseProberEntity() for reasons.
        Field lastUpdateTime = BackupGroupRoot.class.getDeclaredField("updateTimestamp");
        lastUpdateTime.setAccessible(true);
        lastUpdateTime.set(eppResource, null);
        Field deletionTime = EppResource.class.getDeclaredField("deletionTime");
        deletionTime.setAccessible(true);
        deletionTime.set(eppResource, null);
      }
      Field authField =
          eppResource instanceof DomainContent
              ? DomainContent.class.getDeclaredField("authInfo")
              : eppResource instanceof ContactBase
                  ? ContactBase.class.getDeclaredField("authInfo")
                  : null;
      if (authField != null) {
        authField.setAccessible(true);
        AuthInfo authInfo = (AuthInfo) authField.get(eppResource);
        // When AuthInfo is missing, the authInfo field is null if the object is loaded from
        // Datastore, or a PasswordAuth with null properties if loaded from SQL. In the second case
        // we set the authInfo field to null.
        if (authInfo != null
            && authInfo.getPw() != null
            && authInfo.getPw().getRepoId() == null
            && authInfo.getPw().getValue() == null) {
          authField.set(eppResource, null);
        }
      }

      Field field = EppResource.class.getDeclaredField("revisions");
      field.setAccessible(true);
      field.set(eppResource, null);
      return eppResource;
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * Normalizes a {@link HistoryEntry} for comparison.
   *
   * <p>This method modifies the input using reflection because relevant builder methods performs
   * unwanted checks and changes.
   */
  static HistoryEntry normalizeHistoryEntry(HistoryEntry historyEntry) {
    // History objects from Datastore do not have details of their EppResource objects
    // (domainContent, contactBase, hostBase).
    try {
      if (historyEntry instanceof DomainHistory) {
        Field domainContent = DomainHistory.class.getDeclaredField("domainContent");
        domainContent.setAccessible(true);
        domainContent.set(historyEntry, null);
        // Convert empty domainTransactionRecords to null for comparison.
        Field domainTransactionRecords =
            HistoryEntry.class.getDeclaredField("domainTransactionRecords");
        domainTransactionRecords.setAccessible(true);
        Set<?> domainTransactionRecordsValue = (Set<?>) domainTransactionRecords.get(historyEntry);
        if (domainTransactionRecordsValue != null && domainTransactionRecordsValue.isEmpty()) {
          domainTransactionRecords.set(historyEntry, null);
        }
        // DomainHistory in Datastore does not have the following properties either:
        Field nsHosts = DomainHistory.class.getDeclaredField("nsHosts");
        nsHosts.setAccessible(true);
        nsHosts.set(historyEntry, null);
        Field dsDataHistories = DomainHistory.class.getDeclaredField("dsDataHistories");
        dsDataHistories.setAccessible(true);
        dsDataHistories.set(historyEntry, null);
        Field gracePeriodHistories = DomainHistory.class.getDeclaredField("gracePeriodHistories");
        gracePeriodHistories.setAccessible(true);
        gracePeriodHistories.set(historyEntry, null);
      } else if (historyEntry instanceof ContactHistory) {
        Field contactBase = ContactHistory.class.getDeclaredField("contactBase");
        contactBase.setAccessible(true);
        contactBase.set(historyEntry, null);
      } else if (historyEntry instanceof HostHistory) {
        Field hostBase = HostHistory.class.getDeclaredField("hostBase");
        hostBase.setAccessible(true);
        hostBase.set(historyEntry, null);
      }
      return historyEntry;
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * Returns {@code true} if {@code entity} is created by the prober and needs special treatment.
   *
   * <p>{@link EppResource} entities created by the prober are deleted by a cron job that bypasses
   * the CommitLog mechanism. As a result, their deletions are not propagated to SQL, creating two
   * types of mismatches: an entity exists in both databases but differs in lastUpdateTime and
   * deletionTime; an entity only exists in the SQL database.
   *
   * <p>In production, there are few placeholder {@link Registrar registrars} that do not exist in
   * Datastore. They were manually created to in SQL to solve a one-time problem (see b/187946868
   * for details). They can be ignored in the database comparison.
   */
  static boolean isSpecialCaseProberEntity(Object entity) {
    if (entity instanceof EppResource) {
      EppResource host = (EppResource) entity;
      if (host.getPersistedCurrentSponsorRegistrarId().startsWith("prober-")) {
        return true;
      }
    }
    if (entity instanceof HistoryEntry) {
      HistoryEntry historyEntry = (HistoryEntry) entity;
      if (historyEntry.getRegistrarId().startsWith("prober-")) {
        // Not all prober entities have "prober-" as registrar prefix.
        return true;
      }
      if (Objects.equals(historyEntry.getReason(), "Deletion of prober data")) {
        // Soft-delete event in Datastore that is not propagated to SQL.
        return true;
      }
    }
    if (entity instanceof DomainHistory) {
      DomainHistory domainHistory = (DomainHistory) entity;
      if (domainHistory.getDomainContent().isPresent()
          && domainHistory.getDomainContent().get().getDomainName().startsWith("prober-")) {
        // Asynchronously replicated event in SQL.
        return true;
      }
      if (domainHistory.getDomainRepoId() != null) {
        // Some synthetic events only have domainRepoId.
        String repoId = domainHistory.getDomainRepoId();
        if (Transforms.IGNORED_DOMAINS.contains(repoId)) {
          return true;
        }
        String suffix = repoId.substring(repoId.indexOf('-') + 1);
        String cell = suffix.substring(0, 2);
        suffix = suffix.substring(2);
        if (PROBER_CELLS.contains(cell) && PROBER_TYPES.contains(suffix)) {
          return true;
        }
      }
    }
    if (entity instanceof ContactHistory) {
      if (Transforms.IGNORED_CONTACTS.contains(((ContactHistory) entity).getContactRepoId())) {
        return true;
      }
    }
    if (entity instanceof HostHistory) {
      if (Transforms.IGNORED_HOSTS.contains(((HostHistory) entity).getHostRepoId())) {
        return true;
      }
    }
    if (entity instanceof BillingEvent) {
      BillingEvent event = (BillingEvent) entity;
      if (event.getRegistrarId().startsWith("prober-")) {
        return true;
      }
    }
    if (entity instanceof PollMessage) {
      if (((PollMessage) entity).getRegistrarId().startsWith("prober-")) {
        return true;
      }
    }
    if (RegistryEnvironment.get().equals(RegistryEnvironment.PRODUCTION)
        && entity instanceof Registrar) {
      Registrar registrar = (Registrar) entity;
      if (registrar.getRegistrarId().startsWith("prober-wj-")) {
        return true;
      }
    }
    return false;
  }
}
