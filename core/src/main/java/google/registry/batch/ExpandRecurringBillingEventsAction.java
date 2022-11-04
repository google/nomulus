// Copyright 2017 The Nomulus Authors. All Rights Reserved.
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

package google.registry.batch;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static com.google.common.collect.Sets.difference;
import static com.google.common.collect.Sets.newHashSet;
import static google.registry.batch.BatchModule.PARAM_DRY_RUN;
import static google.registry.model.common.Cursor.CursorType.RECURRING_BILLING;
import static google.registry.model.domain.Period.Unit.YEARS;
import static google.registry.model.reporting.HistoryEntry.Type.DOMAIN_AUTORENEW;
import static google.registry.persistence.transaction.QueryComposer.Comparator.EQ;
import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.util.CollectionUtils.union;
import static google.registry.util.DateTimeUtils.START_OF_TIME;
import static google.registry.util.DateTimeUtils.earliestOf;
import static google.registry.util.DomainNameUtils.getTldFromDomainName;

import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Range;
import com.google.common.collect.Streams;
import com.google.common.flogger.FluentLogger;
import google.registry.config.RegistryConfig.Config;
import google.registry.flows.domain.DomainPricingLogic;
import google.registry.model.ImmutableObject;
import google.registry.model.billing.BillingEvent;
import google.registry.model.billing.BillingEvent.Flag;
import google.registry.model.billing.BillingEvent.OneTime;
import google.registry.model.billing.BillingEvent.Recurring;
import google.registry.model.common.Cursor;
import google.registry.model.domain.Domain;
import google.registry.model.domain.DomainHistory;
import google.registry.model.domain.Period;
import google.registry.model.reporting.DomainTransactionRecord;
import google.registry.model.reporting.DomainTransactionRecord.TransactionReportField;
import google.registry.model.tld.Registry;
import google.registry.persistence.VKey;
import google.registry.request.Action;
import google.registry.request.Parameter;
import google.registry.request.Response;
import google.registry.request.auth.Auth;
import google.registry.util.Clock;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import org.joda.time.DateTime;

/**
 * An action that expands {@link Recurring} billing events into synthetic {@link OneTime} events.
 *
 * <p>The cursor used throughout this action (overridden if necessary using the parameter {@code
 * cursorTime}) represents the inclusive lower bound on the range of billing times that will be
 * expanded as a result of the job (the exclusive upper bound being the execution time of the job).
 */
@Action(
    service = Action.Service.BACKEND,
    path = "/_dr/task/expandRecurringBillingEvents",
    auth = Auth.AUTH_INTERNAL_OR_ADMIN)
public class ExpandRecurringBillingEventsAction implements Runnable {

  public static final String PARAM_CURSOR_TIME = "cursorTime";
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  @Inject Clock clock;

  @Inject
  @Config("jdbcBatchSize")
  int batchSize;

  @Inject @Parameter(PARAM_DRY_RUN) boolean isDryRun;
  @Inject @Parameter(PARAM_CURSOR_TIME) Optional<DateTime> cursorTimeParam;

  @Inject DomainPricingLogic domainPricingLogic;
  @Inject Response response;
  @Inject ExpandRecurringBillingEventsAction() {}

  @Override
  public void run() {
    DateTime executeTime = clock.nowUtc();
    DateTime persistedCursorTime =
        tm().transact(
                () ->
                    tm().loadByKeyIfPresent(Cursor.createGlobalVKey(RECURRING_BILLING))
                        .orElse(Cursor.createGlobal(RECURRING_BILLING, START_OF_TIME))
                        .getCursorTime());
    DateTime cursorTime = cursorTimeParam.orElse(persistedCursorTime);
    checkArgument(
        cursorTime.isBefore(executeTime), "Cursor time must be earlier than execution time.");
    logger.atInfo().log(
        "Running Recurring billing event expansion for billing time range [%s, %s).",
        cursorTime, executeTime);
    expandSqlBillingEventsInBatches(executeTime, cursorTime, persistedCursorTime);
  }

  private void expandSqlBillingEventsInBatches(
      DateTime executeTime, DateTime cursorTime, DateTime persistedCursorTime) {
    int totalBillingEventsSaved = 0;
    long maxProcessedRecurrenceId = 0;
    SqlBatchResults sqlBatchResults;

    do {
      final long prevMaxProcessedRecurrenceId = maxProcessedRecurrenceId;
      sqlBatchResults =
          jpaTm()
              .transact(
                  () -> {
                    Set<String> expandedDomains = newHashSet();
                    int batchBillingEventsSaved = 0;
                    long maxRecurrenceId = prevMaxProcessedRecurrenceId;
                    List<Recurring> recurrings =
                        jpaTm()
                            .query(
                                "FROM BillingRecurrence "
                                    + "WHERE eventTime <= :executeTime "
                                    + "AND eventTime < recurrenceEndTime "
                                    + "AND id > :maxProcessedRecurrenceId "
                                    + "AND recurrenceEndTime > :cursorTime "
                                    + "ORDER BY id ASC",
                                Recurring.class)
                            .setParameter("executeTime", executeTime)
                            .setParameter("maxProcessedRecurrenceId", prevMaxProcessedRecurrenceId)
                            .setParameter("cursorTime", cursorTime)
                            .setMaxResults(batchSize)
                            .getResultList();
                    for (Recurring recurring : recurrings) {
                      if (expandedDomains.contains(recurring.getTargetId())) {
                        // On the off chance this batch contains multiple recurrences for the same
                        // domain (which is actually possible if a given domain is quickly renewed
                        // multiple times in a row), then short-circuit after the first one is
                        // processed that involves actually expanding a billing event. This is
                        // necessary because otherwise we get an "Inserted/updated object reloaded"
                        // error from Hibernate when those billing events would be loaded
                        // inside a transaction where they were already written. Note, there is no
                        // actual further work to be done in this case anyway, not unless it has
                        // somehow been over a year since this action last ran successfully (and if
                        // that were somehow true, the remaining billing events would still be
                        // expanded on subsequent runs).
                        continue;
                      }
                      int billingEventsSaved =
                          expandBillingEvent(
                              recurring, executeTime, cursorTime, isDryRun, domainPricingLogic);
                      batchBillingEventsSaved += billingEventsSaved;
                      if (billingEventsSaved > 0) {
                        expandedDomains.add(recurring.getTargetId());
                      }
                      maxRecurrenceId = Math.max(maxRecurrenceId, recurring.getId());
                    }
                    return SqlBatchResults.create(
                        batchBillingEventsSaved,
                        maxRecurrenceId,
                        maxRecurrenceId > prevMaxProcessedRecurrenceId);
                  });
      totalBillingEventsSaved += sqlBatchResults.batchBillingEventsSaved();
      maxProcessedRecurrenceId = sqlBatchResults.maxProcessedRecurrenceId();
      if (sqlBatchResults.batchBillingEventsSaved() > 0) {
        logger.atInfo().log(
            "Saved %d billing events in batch (%d total) with max recurrence id %d.",
            sqlBatchResults.batchBillingEventsSaved(),
            totalBillingEventsSaved,
            maxProcessedRecurrenceId);
      } else {
        // If we're churning through a lot of no-op recurrences that don't need expanding (yet?),
        // then only log one no-op every so often as a good balance between letting the user track
        // that the action is still running while also not spamming the logs incessantly.
        logger.atInfo().atMostEvery(3, TimeUnit.MINUTES).log(
            "Processed up to max recurrence id %d (no billing events saved recently).",
            maxProcessedRecurrenceId);
      }
    } while (sqlBatchResults.shouldContinue());

    if (!isDryRun) {
      logger.atInfo().log("Saved %d total OneTime billing events.", totalBillingEventsSaved);
    } else {
      logger.atInfo().log(
          "Generated %d total OneTime billing events (dry run).", totalBillingEventsSaved);
    }
    logger.atInfo().log(
        "Recurring event expansion %s complete for billing event range [%s, %s).",
        isDryRun ? "(dry run) " : "", cursorTime, executeTime);
    tm().transact(
            () -> {
              // Check for the unlikely scenario where the cursor has been altered during the
              // expansion.
              DateTime currentCursorTime =
                  tm().loadByKeyIfPresent(Cursor.createGlobalVKey(RECURRING_BILLING))
                      .orElse(Cursor.createGlobal(RECURRING_BILLING, START_OF_TIME))
                      .getCursorTime();
              if (!currentCursorTime.equals(persistedCursorTime)) {
                throw new IllegalStateException(
                    String.format(
                        "Current cursor position %s does not match persisted cursor position %s.",
                        currentCursorTime, persistedCursorTime));
              }
              if (!isDryRun) {
                tm().put(Cursor.createGlobal(RECURRING_BILLING, executeTime));
              }
            });
  }

  @AutoValue
  abstract static class SqlBatchResults {
    abstract int batchBillingEventsSaved();

    abstract long maxProcessedRecurrenceId();

    abstract boolean shouldContinue();

    static SqlBatchResults create(
        int batchBillingEventsSaved, long maxProcessedRecurrenceId, boolean shouldContinue) {
      return new AutoValue_ExpandRecurringBillingEventsAction_SqlBatchResults(
          batchBillingEventsSaved, maxProcessedRecurrenceId, shouldContinue);
    }
  }

  private static int expandBillingEvent(
      Recurring recurring,
      DateTime executeTime,
      DateTime cursorTime,
      boolean isDryRun,
      DomainPricingLogic domainPricingLogic) {
    ImmutableSet.Builder<OneTime> syntheticOneTimesBuilder = new ImmutableSet.Builder<>();
    final Registry tld = Registry.get(getTldFromDomainName(recurring.getTargetId()));

    // Determine the complete set of times at which this recurring event should
    // occur (up to and including the runtime of the action).
    Iterable<DateTime> eventTimes =
        recurring
            .getRecurrenceTimeOfYear()
            .getInstancesInRange(
                Range.closed(
                    recurring.getEventTime(),
                    earliestOf(recurring.getRecurrenceEndTime(), executeTime)));

    // Convert these event times to billing times
    final ImmutableSet<DateTime> billingTimes =
        getBillingTimesInScope(eventTimes, cursorTime, executeTime, tld);

    VKey<Domain> domainKey = VKey.create(Domain.class, recurring.getDomainRepoId());
    Iterable<OneTime> oneTimesForDomain;
    oneTimesForDomain =
        tm().createQueryComposer(OneTime.class)
            .where("domainRepoId", EQ, recurring.getDomainRepoId())
            .list();

    // Determine the billing times that already have OneTime events persisted.
    ImmutableSet<DateTime> existingBillingTimes =
        getExistingBillingTimes(oneTimesForDomain, recurring);

    ImmutableSet.Builder<DomainHistory> historyEntriesBuilder = new ImmutableSet.Builder<>();
    // Create synthetic OneTime events for all billing times that do not yet have
    // an event persisted.
    for (DateTime billingTime : difference(billingTimes, existingBillingTimes)) {
      // Construct a new HistoryEntry that parents over the OneTime
      DomainHistory historyEntry =
          new DomainHistory.Builder()
              .setBySuperuser(false)
              .setRegistrarId(recurring.getRegistrarId())
              .setModificationTime(tm().getTransactionTime())
              .setDomain(tm().loadByKey(domainKey))
              .setPeriod(Period.create(1, YEARS))
              .setReason("Domain autorenewal by ExpandRecurringBillingEventsAction")
              .setRequestedByRegistrar(false)
              .setType(DOMAIN_AUTORENEW)
              // Don't write a domain transaction record if the recurrence was
              // ended prior to the billing time (i.e. a domain was deleted
              // during the autorenew grace period).
              .setDomainTransactionRecords(
                  recurring.getRecurrenceEndTime().isBefore(billingTime)
                      ? ImmutableSet.of()
                      : ImmutableSet.of(
                          DomainTransactionRecord.create(
                              tld.getTldStr(),
                              // We report this when the autorenew grace period
                              // ends
                              billingTime,
                              TransactionReportField.netRenewsFieldFromYears(1),
                              1)))
              .build();
      historyEntriesBuilder.add(historyEntry);

      DateTime eventTime = billingTime.minus(tld.getAutoRenewGracePeriodLength());

      syntheticOneTimesBuilder.add(
          new OneTime.Builder()
              .setBillingTime(billingTime)
              .setRegistrarId(recurring.getRegistrarId())
              // Determine the cost for a one-year renewal.
              .setCost(
                  domainPricingLogic
                      .getRenewPrice(tld, recurring.getTargetId(), eventTime, 1, recurring)
                      .getRenewCost())
              .setEventTime(eventTime)
              .setFlags(union(recurring.getFlags(), Flag.SYNTHETIC))
              .setDomainHistory(historyEntry)
              .setPeriodYears(1)
              .setReason(recurring.getReason())
              .setSyntheticCreationTime(executeTime)
              .setCancellationMatchingBillingEvent(recurring)
              .setTargetId(recurring.getTargetId())
              .build());
    }
    Set<DomainHistory> historyEntries = historyEntriesBuilder.build();
    Set<OneTime> syntheticOneTimes = syntheticOneTimesBuilder.build();
    if (!isDryRun) {
      ImmutableSet<ImmutableObject> entitiesToSave =
          new ImmutableSet.Builder<ImmutableObject>()
              .addAll(historyEntries)
              .addAll(syntheticOneTimes)
              .build();
      tm().putAll(entitiesToSave);
    }
    return syntheticOneTimes.size();
  }

  /**
   * Filters a set of {@link DateTime}s down to event times that are in scope for a particular
   * action run, given the cursor time and the action execution time.
   */
  protected static ImmutableSet<DateTime> getBillingTimesInScope(
      Iterable<DateTime> eventTimes,
      DateTime cursorTime,
      DateTime executeTime,
      final Registry tld) {
    return Streams.stream(eventTimes)
        .map(eventTime -> eventTime.plus(tld.getAutoRenewGracePeriodLength()))
        .filter(Range.closedOpen(cursorTime, executeTime))
        .collect(toImmutableSet());
  }

  /**
   * Determines an {@link ImmutableSet} of {@link DateTime}s that have already been persisted for a
   * given recurring billing event.
   */
  private static ImmutableSet<DateTime> getExistingBillingTimes(
      Iterable<BillingEvent.OneTime> oneTimesForDomain,
      final BillingEvent.Recurring recurringEvent) {
    return Streams.stream(oneTimesForDomain)
        .filter(
            billingEvent ->
                recurringEvent
                    .createVKey()
                    .equals(billingEvent.getCancellationMatchingBillingEvent()))
        .map(OneTime::getBillingTime)
        .collect(toImmutableSet());
  }
}
