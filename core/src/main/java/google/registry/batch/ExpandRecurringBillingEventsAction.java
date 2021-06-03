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
import static google.registry.mapreduce.MapreduceRunner.PARAM_DRY_RUN;
import static google.registry.mapreduce.inputs.EppResourceInputs.createChildEntityInput;
import static google.registry.model.common.Cursor.CursorType.RECURRING_BILLING;
import static google.registry.model.domain.Period.Unit.YEARS;
import static google.registry.model.ofy.ObjectifyService.auditedOfy;
import static google.registry.model.reporting.HistoryEntry.Type.DOMAIN_AUTORENEW;
import static google.registry.persistence.transaction.QueryComposer.Comparator.EQ;
import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.persistence.transaction.TransactionManagerUtil.transactIfJpaTm;
import static google.registry.pricing.PricingEngineProxy.getDomainRenewCost;
import static google.registry.util.CollectionUtils.union;
import static google.registry.util.DateTimeUtils.START_OF_TIME;
import static google.registry.util.DateTimeUtils.earliestOf;
import static google.registry.util.DomainNameUtils.getTldFromDomainName;

import com.google.appengine.tools.mapreduce.Mapper;
import com.google.appengine.tools.mapreduce.Reducer;
import com.google.appengine.tools.mapreduce.ReducerInput;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Range;
import com.google.common.collect.Streams;
import com.google.common.flogger.FluentLogger;
import google.registry.mapreduce.MapreduceRunner;
import google.registry.mapreduce.inputs.NullInput;
import google.registry.model.ImmutableObject;
import google.registry.model.billing.BillingEvent;
import google.registry.model.billing.BillingEvent.Flag;
import google.registry.model.billing.BillingEvent.OneTime;
import google.registry.model.billing.BillingEvent.Recurring;
import google.registry.model.common.Cursor;
import google.registry.model.domain.DomainBase;
import google.registry.model.domain.DomainHistory;
import google.registry.model.domain.Period;
import google.registry.model.registry.Registry;
import google.registry.model.reporting.DomainTransactionRecord;
import google.registry.model.reporting.DomainTransactionRecord.TransactionReportField;
import google.registry.persistence.VKey;
import google.registry.request.Action;
import google.registry.request.Parameter;
import google.registry.request.Response;
import google.registry.request.auth.Auth;
import google.registry.util.Clock;
import java.util.Optional;
import java.util.Set;
import javax.inject.Inject;
import org.joda.money.Money;
import org.joda.time.DateTime;

/**
 * A mapreduce that expands {@link Recurring} billing events into synthetic {@link OneTime} events.
 *
 * <p>The cursor used throughout this mapreduce (overridden if necessary using the parameter {@code
 * cursorTime}) represents the inclusive lower bound on the range of billing times that will be
 * expanded as a result of the job (the exclusive upper bound being the execution time of the job).
 */
@Action(
    service = Action.Service.BACKEND,
    path = "/_dr/task/expandRecurringBillingEvents",
    auth = Auth.AUTH_INTERNAL_OR_ADMIN)
public class ExpandRecurringBillingEventsAction implements Runnable {

  public static final String PARAM_CURSOR_TIME = "cursorTime";
  private static final String ERROR_COUNTER = "errors";
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  @Inject Clock clock;
  @Inject MapreduceRunner mrRunner;
  @Inject @Parameter(PARAM_DRY_RUN) boolean isDryRun;
  @Inject @Parameter(PARAM_CURSOR_TIME) Optional<DateTime> cursorTimeParam;
  @Inject Response response;
  @Inject ExpandRecurringBillingEventsAction() {}

  @Override
  public void run() {
    DateTime executeTime = clock.nowUtc();
    DateTime persistedCursorTime =
        transactIfJpaTm(
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
    if (tm().isOfy()) {
      mrRunner
          .setJobName("Expand Recurring billing events into synthetic OneTime events.")
          .setModuleName("backend")
          .runMapreduce(
              new ExpandRecurringBillingEventsMapper(isDryRun, cursorTime, clock.nowUtc()),
              new ExpandRecurringBillingEventsReducer(isDryRun, persistedCursorTime),
              // Add an extra shard that maps over a null recurring event (see the mapper for why).
              ImmutableList.of(
                  new NullInput<>(),
                  createChildEntityInput(
                      ImmutableSet.of(DomainBase.class), ImmutableSet.of(Recurring.class))))
          .sendLinkToMapreduceConsole(response);
    } else {
      int numBillingEventsSaved =
          jpaTm()
              .transact(
                  () ->
                      jpaTm()
                          .query(
                              "FROM BillingRecurrence "
                                  + "WHERE event_time <= :executeTime "
                                  + "AND event_time < recurrence_end_time",
                              Recurring.class)
                          .setParameter("executeTime", executeTime.toDate())
                          // Need to get a list from the transaction and then convert it to a stream
                          // for further processing. If we get a stream directly, each elements gets
                          // processed downstream eagerly but Hibernate returns a
                          // ScrollableResultsIterator that cannot be advanced outside the
                          // transaction, resulting in an exception.
                          .getResultList())
              .stream()
              .map(
                  recurring ->
                      jpaTm()
                          .transact(
                              () ->
                                  expandBillingEvent(recurring, executeTime, cursorTime, isDryRun)))
              .reduce(0, Integer::sum);

      if (!isDryRun) {
        logger.atInfo().log("Saved OneTime billing events", numBillingEventsSaved);
      } else {
        logger.atInfo().log("Generated OneTime billing events (dry run)", numBillingEventsSaved);
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
  }
  /** Mapper to expand {@link Recurring} billing events into synthetic {@link OneTime} events. */
  public static class ExpandRecurringBillingEventsMapper
      extends Mapper<Recurring, DateTime, DateTime> {

    private static final long serialVersionUID = 8376442755556228455L;

    private final boolean isDryRun;
    private final DateTime cursorTime;
    private final DateTime executeTime;

    public ExpandRecurringBillingEventsMapper(
        boolean isDryRun, DateTime cursorTime, DateTime executeTime) {
      this.isDryRun = isDryRun;
      this.cursorTime = cursorTime;
      this.executeTime = executeTime;
    }

    @Override
    public final void map(final Recurring recurring) {
      // This single emit forces the reducer to run at the end of the map job, so that a mapper
      // that runs without error will advance the cursor at the end of processing (unless this was
      // a dry run, in which case the cursor should not be advanced).
      if (recurring == null) {
        emit(cursorTime, executeTime);
        return;
      }
      getContext().incrementCounter("Recurring billing events encountered");
      // Ignore any recurring billing events that have yet to apply.
      if (recurring.getEventTime().isAfter(executeTime)
          // This second case occurs when a domain is transferred or deleted before first renewal.
          || recurring.getRecurrenceEndTime().isBefore(recurring.getEventTime())) {
        getContext().incrementCounter("Recurring billing events ignored");
        return;
      }
      int numBillingEventsSaved = 0;
      try {
        numBillingEventsSaved =
            tm().transactNew(
                    () -> expandBillingEvent(recurring, executeTime, cursorTime, isDryRun));
      } catch (Throwable t) {
        getContext().incrementCounter("error: " + t.getClass().getSimpleName());
        getContext().incrementCounter(ERROR_COUNTER);
        throw new RuntimeException(
            String.format(
                "Error while expanding Recurring billing events for %d", recurring.getId()),
            t);
      }
      if (!isDryRun) {
        getContext().incrementCounter("Saved OneTime billing events", numBillingEventsSaved);
      } else {
        getContext()
            .incrementCounter("Generated OneTime billing events (dry run)", numBillingEventsSaved);
      }
    }
  }

  /**
   * "Reducer" to advance the cursor after all map jobs have been completed. The NullInput into the
   * mapper will cause the mapper to emit one timestamp pair (current cursor and execution time),
   * and the cursor will be advanced (and the timestamps logged) at the end of a successful
   * mapreduce.
   */
  public static class ExpandRecurringBillingEventsReducer
      extends Reducer<DateTime, DateTime, Void> {

    private final boolean isDryRun;
    private final DateTime expectedPersistedCursorTime;

    public ExpandRecurringBillingEventsReducer(
        boolean isDryRun, DateTime expectedPersistedCursorTime) {
      this.isDryRun = isDryRun;
      this.expectedPersistedCursorTime = expectedPersistedCursorTime;
    }

    @Override
    public void reduce(final DateTime cursorTime, final ReducerInput<DateTime> executionTimeInput) {
      if (getContext().getCounter(ERROR_COUNTER).getValue() > 0) {
        logger.atSevere().log(
            "One or more errors logged during recurring event expansion. Cursor will"
                + " not be advanced.");
        return;
      }
      final DateTime executionTime = executionTimeInput.next();
      logger.atInfo().log(
          "Recurring event expansion %s complete for billing event range [%s, %s).",
          isDryRun ? "(dry run) " : "", cursorTime, executionTime);
      tm().transact(
              () -> {
                Cursor cursor =
                    auditedOfy().load().key(Cursor.createGlobalKey(RECURRING_BILLING)).now();
                DateTime currentCursorTime =
                    (cursor == null ? START_OF_TIME : cursor.getCursorTime());
                if (!currentCursorTime.equals(expectedPersistedCursorTime)) {
                  logger.atSevere().log(
                      "Current cursor position %s does not match expected cursor position %s.",
                      currentCursorTime, expectedPersistedCursorTime);
                  return;
                }
                if (!isDryRun) {
                  tm().put(Cursor.createGlobal(RECURRING_BILLING, executionTime));
                }
              });
    }
  }

  private static int expandBillingEvent(
      Recurring recurring, DateTime executeTime, DateTime cursorTime, boolean isDryRun) {
    ImmutableSet.Builder<OneTime> syntheticOneTimesBuilder = new ImmutableSet.Builder<>();
    final Registry tld = Registry.get(getTldFromDomainName(recurring.getTargetId()));

    // Determine the complete set of times at which this recurring event should
    // occur (up to and including the runtime of the mapreduce).
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

    VKey<DomainBase> domainKey =
        VKey.create(
            DomainBase.class, recurring.getDomainRepoId(), recurring.getParentKey().getParent());
    Iterable<OneTime> oneTimesForDomain;
    if (tm().isOfy()) {
      oneTimesForDomain = auditedOfy().load().type(OneTime.class).ancestor(domainKey.getOfyKey());
    } else {
      oneTimesForDomain =
          tm().createQueryComposer(OneTime.class)
              .where("domainRepoId", EQ, recurring.getDomainRepoId())
              .list();
    }

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
              .setClientId(recurring.getClientId())
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
      // Determine the cost for a one-year renewal.
      Money renewCost = getDomainRenewCost(recurring.getTargetId(), eventTime, 1);
      syntheticOneTimesBuilder.add(
          new OneTime.Builder()
              .setBillingTime(billingTime)
              .setClientId(recurring.getClientId())
              .setCost(renewCost)
              .setEventTime(eventTime)
              .setFlags(union(recurring.getFlags(), Flag.SYNTHETIC))
              .setParent(historyEntry)
              .setPeriodYears(1)
              .setReason(recurring.getReason())
              .setSyntheticCreationTime(executeTime)
              .setCancellationMatchingBillingEvent(recurring.createVKey())
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
   * mapreduce run, given the cursor time and the mapreduce execution time.
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
