// Copyright 2022 The Nomulus Authors. All Rights Reserved.
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

package google.registry.beam.billing;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.Sets.difference;
import static google.registry.model.common.Cursor.CursorType.RECURRING_BILLING;
import static google.registry.model.domain.Period.Unit.YEARS;
import static google.registry.model.reporting.HistoryEntry.Type.DOMAIN_AUTORENEW;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.util.CollectionUtils.union;
import static google.registry.util.DateTimeUtils.START_OF_TIME;
import static google.registry.util.DateTimeUtils.earliestOf;
import static google.registry.util.DateTimeUtils.latestOf;
import static org.apache.beam.sdk.values.TypeDescriptors.voids;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Range;
import dagger.Component;
import google.registry.beam.common.RegistryJpaIO;
import google.registry.config.RegistryConfig.Config;
import google.registry.config.RegistryConfig.ConfigModule;
import google.registry.flows.custom.CustomLogicFactoryModule;
import google.registry.flows.custom.CustomLogicModule;
import google.registry.flows.domain.DomainPricingLogic;
import google.registry.model.ImmutableObject;
import google.registry.model.billing.BillingEvent.Cancellation;
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
import google.registry.persistence.PersistenceModule.TransactionIsolationLevel;
import google.registry.util.Clock;
import google.registry.util.SystemClock;
import java.io.Serializable;
import java.math.BigInteger;
import java.util.Set;
import javax.inject.Singleton;
import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.PipelineResult;
import org.apache.beam.sdk.coders.KvCoder;
import org.apache.beam.sdk.coders.VarIntCoder;
import org.apache.beam.sdk.coders.VarLongCoder;
import org.apache.beam.sdk.metrics.Counter;
import org.apache.beam.sdk.metrics.Metrics;
import org.apache.beam.sdk.options.PipelineOptionsFactory;
import org.apache.beam.sdk.transforms.Create;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.GroupIntoBatches;
import org.apache.beam.sdk.transforms.MapElements;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.beam.sdk.transforms.Wait;
import org.apache.beam.sdk.values.KV;
import org.apache.beam.sdk.values.PCollection;
import org.apache.beam.sdk.values.PDone;
import org.joda.time.DateTime;

/**
 * Definition of a Dataflow Flex pipeline template, which expands {@link Recurring} to {@link
 * OneTime} when an autorenew occurs within the given time frame.
 *
 * <p>This pipeline works in three stages:
 *
 * <ul>
 *   <li>Gather the {@link Recurring}s that are in scope for expansion. The exact condition of
 *       {@link Recurring}s to include can be found in {@link #getRecurringsInScope(Pipeline)}.
 *   <li>Expand the {@link Recurring}s to {@link OneTime} (and corresponding {@link DomainHistory})
 *       that fall within the [{@link #startTime}, {@link #endTime}) window, excluding those that
 *       are already present (to make this pipeline idempotent when running with the same parameters
 *       multiple times, either in parallel or in sequence). The {@link Recurring} is also updated
 *       with the information on when it was last expanded, so it would not be in scope for
 *       expansion until at least a year later.
 *   <li>If the cursor for billing events should be advanced, advance it to {@link #endTime} after
 *       all of the expansions in the previous step is done, only when it is currently at {@link
 *       #startTime}.
 * </ul>
 *
 * <p>Note that the creation of new {@link OneTime} and {@link DomainHistory} is done speculatively
 * as soon as its event time is in scope for expansion (i.e. within the window of operation). If a
 * domain is subsequently cancelled during the autorenew grace period, a {@link Cancellation} would
 * have been created to cancel the {@link OneTime} out. Similarly, a {@link DomainHistory} for the
 * delete will be created which negates the effect of the speculatively created {@link
 * DomainHistory}, specifically for the transaction records. Both the {@link OneTime} and {@link
 * DomainHistory} will only be used (and cancelled out) when the billing time becomes effective,
 * which is after the grace period, when the cancellations would have been written, if need be. This
 * is no different from what we do with manual renewals or normal creates, where entities are always
 * created for the action regardless of whether their effects will be negated later due to
 * subsequent actions within respective grace periods.
 *
 * <p>To stage this template locally, run {@code ./nom_build :core:sBP --environment=alpha \
 * --pipeline=expandBilling}.
 *
 * <p>Then, you can run the staged template via the API client library, gCloud or a raw REST call.
 *
 * @see Cancellation#forGracePeriod
 * @see google.registry.flows.domain.DomainFlowUtils#createCancelingRecords
 * @see <a href="https://cloud.google.com/dataflow/docs/guides/templates/using-flex-templates">Using
 *     Flex Templates</a>
 */
public class ExpandRecurringBillingEventsPipeline implements Serializable {

  private static final long serialVersionUID = -5827984301386630194L;

  private static final DomainPricingLogic domainPricingLogic;

  private static final int batchSize;

  static {
    PipelineComponent pipelineComponent =
        DaggerExpandRecurringBillingEventsPipeline_PipelineComponent.create();
    domainPricingLogic = pipelineComponent.domainPricingLogic();
    batchSize = pipelineComponent.batchSize();
  }

  // Inclusive lower bound of the expansion window.
  private final DateTime startTime;
  // Exclusive lower bound of the expansion window.
  private final DateTime endTime;
  private final boolean isDryRun;
  private final boolean advanceCursor;
  private final Counter recurringsInScopeCounter =
      Metrics.counter("ExpandBilling", "RecurringsInScope");
  private final Counter expandedOneTimeCounter =
      Metrics.counter("ExpandBilling", "ExpandedOneTime");

  ExpandRecurringBillingEventsPipeline(
      ExpandRecurringBillingEventsPipelineOptions options, Clock clock) {
    startTime = DateTime.parse(options.getStartTime());
    endTime = DateTime.parse(options.getEndTime());
    checkArgument(
        !endTime.isAfter(clock.nowUtc()),
        String.format("End time %s must be at or before now.", endTime));
    checkArgument(
        startTime.isBefore(endTime),
        String.format("[%s, %s) is not a valid window of operation.", startTime, endTime));
    isDryRun = options.getIsDryRun();
    advanceCursor = options.getAdvanceCursor();
  }

  private PipelineResult run(Pipeline pipeline) {
    setupPipeline(pipeline);
    return pipeline.run();
  }

  void setupPipeline(Pipeline pipeline) {
    PCollection<KV<Integer, Long>> recurringIds = getRecurringsInScope(pipeline);
    PCollection<Void> expanded = expandRecurrings(recurringIds);
    if (!isDryRun && advanceCursor) {
      advanceCursor(expanded);
    }
  }

  PCollection<KV<Integer, Long>> getRecurringsInScope(Pipeline pipeline) {
    return pipeline.apply(
        "Read all Recurrings in scope",
        // Use native query because JPQL does not support timestamp arithmetics.
        RegistryJpaIO.read(
                "SELECT billing_recurrence_id "
                    + "FROM \"BillingRecurrence\" "
                    // Recurrence should not close before the first event time.
                    + "WHERE event_time < recurrence_end_time "
                    // First event time should be before end time.
                    + "AND event_Time < :endTime "
                    // Recurrence should not close before start time.
                    + "AND :startTime < recurrence_end_time "
                    // Last expansion should happen at least one year before start time.
                    + "AND recurrence_last_expansion < :oneYearAgo "
                    // The recurrence should not close before next expansion time.
                    + "AND recurrence_last_expansion + INTERVAL '1 YEAR' < recurrence_end_time",
                ImmutableMap.of(
                    "endTime",
                    endTime,
                    "startTime",
                    startTime,
                    "oneYearAgo",
                    endTime.minusYears(1)),
                true,
                (BigInteger id) -> {
                  // Note that because all elements are mapped to the same dummy key, the next
                  // batching transform will effectively be serial. This however does not matter for
                  // our use case because the elements were obtained from a SQL read query, which
                  // are returned sequentially already. Therefore, having a sequential step to group
                  // them does not reduce overall parallelism of the pipeline, and the batches can
                  // then be distributed to all available workers for further processing, where the
                  // main benefit of parallelism shows. In benchmarking, turning the distribution
                  // of elements in this step resulted in marginal improvement in overall
                  // performance at best without clear indication on why or to which degree. If the
                  // runtime becomes a concern later on, we could consider fine-tuning the sharding
                  // of output elements in this step.
                  //
                  // See: https://stackoverflow.com/a/44956702/791306
                  return KV.of(0, id.longValue());
                })
            .withCoder(KvCoder.of(VarIntCoder.of(), VarLongCoder.of())));
  }

  private PCollection<Void> expandRecurrings(PCollection<KV<Integer, Long>> recurringIds) {
    return recurringIds
        .apply(
            "Group into batches",
            GroupIntoBatches.<Integer, Long>ofSize(batchSize).withShardedKey())
        .apply(
            "Expand and save Recurrings into OneTimes and corresponding DomainHistories",
            MapElements.into(voids())
                .via(
                    element -> {
                      Iterable<Long> ids = element.getValue();
                      tm().transact(
                              () -> {
                                ImmutableSet.Builder<ImmutableObject> results =
                                    new ImmutableSet.Builder<>();
                                ids.forEach(id -> expandOneRecurring(id, results));
                                if (!isDryRun) {
                                  tm().putAll(results.build());
                                }
                              });
                      return null;
                    }));
  }

  private void expandOneRecurring(Long recurringId, ImmutableSet.Builder<ImmutableObject> results) {
    Recurring recurring = tm().loadByKey(Recurring.createVKey(recurringId));
    recurringsInScopeCounter.inc();
    Domain domain = tm().loadByKey(Domain.createVKey(recurring.getDomainRepoId()));
    Registry tld = Registry.get(domain.getTld());

    // Determine the complete set of EventTimes this recurring event should expand to within
    // [max(recurrenceLastExpansion + 1 yr, startTime), min(recurrenceEndTime, endTime)).
    ImmutableSet<DateTime> eventTimes =
        ImmutableSet.copyOf(
            recurring
                .getRecurrenceTimeOfYear()
                .getInstancesInRange(
                    Range.closedOpen(
                        latestOf(recurring.getRecurrenceLastExpansion().plusYears(1), startTime),
                        earliestOf(recurring.getRecurrenceEndTime(), endTime))));

    // Find the times for which the OneTime billing event are already created, making this expansion
    // idempotent. There is no need to match to the domain repo ID as the cancellation matching
    // billing event itself can only be for a single domain.
    ImmutableSet<DateTime> existingEventTimes =
        ImmutableSet.copyOf(
            tm().query(
                    "SELECT eventTime FROM BillingEvent WHERE cancellationMatchingBillingEvent ="
                        + " :key",
                    DateTime.class)
                .setParameter("key", recurring.createVKey())
                .getResultList());

    Set<DateTime> eventTimesToExpand = difference(eventTimes, existingEventTimes);

    if (eventTimesToExpand.isEmpty()) {
      return;
    }

    DateTime recurrenceLastExpansionTime = recurring.getRecurrenceLastExpansion();

    // Create new OneTime and DomainHistory for EventTimes that needs to be expanded.
    for (DateTime eventTime : eventTimesToExpand) {
      recurrenceLastExpansionTime = latestOf(recurrenceLastExpansionTime, eventTime);
      expandedOneTimeCounter.inc();
      DateTime billingTime = eventTime.plus(tld.getAutoRenewGracePeriodLength());
      // Note that the DomainHistory is created as of transaction time, as opposed to event time.
      // This might be counterintuitive because other DomainHistories are created at the time
      // mutation events occur, such as in DomainDeleteFlow or DomainRenewFlow. Therefore, it is
      // possible to have a DomainHistory for a delete during the autorenew grace period with a
      // modification time before that of the DomainHistory for the autorenew itself. This is not
      // ideal, but necessary because we save the **current** state of the domain (as of transaction
      // time) to the DomainHistory , instead of the state of the domain as of event time (which
      // would required loading the domain from DomainHistory at event time).
      //
      // Even though doing the loading is seemly possible, it generally is a bad idea to create
      // DomainHistories retroactively and in all instances that we create a HistoryEntry we always
      // set the modification time to the transaction time. It would also violate the invariance
      // that a DomainHistory with a higher revision ID (which is always allocated with monotonic
      // increase) always has a later modification time.
      //
      // Lastly because the domain entity itself did not change as part of the expansion, we should
      // not project it to transaction time before saving it in the history, which would require us
      // to save the projected domain as well. Any changes to the domain itself are handled when
      // the domain is actually used or explicitly projected and saved. The DomainHistory created
      // here does not actually affect anything materially (e.g. RDE). We can understand it in such
      // a way that this history represents not when the domain is autorenewed (at event time), but
      // when its autorenew billing event is created (at transaction time).
      DomainHistory historyEntry =
          new DomainHistory.Builder()
              .setBySuperuser(false)
              .setRegistrarId(recurring.getRegistrarId())
              .setModificationTime(tm().getTransactionTime())
              .setDomain(domain)
              .setPeriod(Period.create(1, YEARS))
              .setReason("Domain autorenewal by ExpandRecurringBillingEventsPipeline")
              .setRequestedByRegistrar(false)
              .setType(DOMAIN_AUTORENEW)
              .setDomainTransactionRecords(
                  // Don't write a domain transaction record if the domain is deleted before billing
                  // time (i.e. within the autorenew grace period). We cannot rely on a negating
                  // DomainHistory created by DomainDeleteFlow because it only cancels transaction
                  // records already present. In this case the domain was deleted before this
                  // pipeline runs to expand the OneTime (which should be rare because this pipeline
                  // should run every day), and no negating transaction records would have been
                  // created when the deletion occurred. Again, there is no need to project the
                  // domain, because if it were deleted before this transaction, its updated delete
                  // time would have already been loaded here.
                  //
                  // We don't compare recurrence end time with billing time because the recurrence
                  // could be caused for other reasons during the grace period, like a manual
                  // renewal, in which case we still want to write the transaction record. Also,
                  // the expansion happens when event time is in scope, which means the billing time
                  // is still 45 days in the future, and the recurrence could have been closed
                  // between now and then.
                  //
                  // A side effect of this logic is that if a transfer occurs within the ARGP, it
                  // would have recorded both a TRANSFER_SUCCESSFUL and a NET_RENEWS_1_YEAR, even
                  // though the transfer would have subsumed the autorenew. There is no perfect
                  // solution for this because even if we expand the recurrence when the billing
                  // event is in scope (as was the case in the old action), we still cannot use
                  // recurrence end time < billing time as an indicator for if a transfer had
                  // occurred during ARGP (see last paragraph, renewals during ARGP also close the
                  // recurrence),therefore we still cannot always be correct when constructing the
                  // transaction records that way (either we miss transfers, or we miss renewals
                  // during ARGP).
                  //
                  // See: DomainFlowUtils#createCancellingRecords
                  domain.getDeletionTime().isBefore(billingTime)
                      ? ImmutableSet.of()
                      : ImmutableSet.of(
                          DomainTransactionRecord.create(
                              tld.getTldStr(),
                              // We report this when the autorenew grace period ends.
                              billingTime,
                              TransactionReportField.netRenewsFieldFromYears(1),
                              1)))
              .build();
      results.add(historyEntry);

      // It is OK to always create a OneTime, even though the domain might be deleted or transferred
      // later during autorenew grace period, as a cancellation will always be written out in those
      // instances.
      OneTime oneTime =
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
              .setSyntheticCreationTime(endTime)
              .setCancellationMatchingBillingEvent(recurring)
              .setTargetId(recurring.getTargetId())
              .build();
      results.add(oneTime);
    }
    results.add(
        recurring.asBuilder().setRecurrenceLastExpansion(recurrenceLastExpansionTime).build());
  }

  private PDone advanceCursor(PCollection<Void> persisted) {
    return PDone.in(
        persisted
            .getPipeline()
            .apply("Create one dummy element", Create.of((Void) null))
            .apply("Wait for all saves to finish", Wait.on(persisted))
            // Because only one dummy element is created in the start PCollection, this
            // transform is guaranteed to only process one element and therefore only run once.
            // Because the previous step waits for all emissions of voids from the expansion step to
            // finish, this transform is guaranteed to run only after all expansions are done and
            // persisted.
            .apply(
                "Advance cursor",
                ParDo.of(
                    new DoFn<Void, Void>() {
                      @ProcessElement
                      public void processElement() {
                        tm().transact(
                                () -> {
                                  DateTime currentCursorTime =
                                      tm().loadByKeyIfPresent(
                                              Cursor.createGlobalVKey(RECURRING_BILLING))
                                          .orElse(
                                              Cursor.createGlobal(RECURRING_BILLING, START_OF_TIME))
                                          .getCursorTime();
                                  if (!currentCursorTime.equals(startTime)) {
                                    throw new IllegalStateException(
                                        String.format(
                                            "Current cursor position %s does not match start time"
                                                + " %s.",
                                            currentCursorTime, startTime));
                                  }
                                  tm().put(Cursor.createGlobal(RECURRING_BILLING, endTime));
                                });
                      }
                    }))
            .getPipeline());
  }

  public static void main(String[] args) {
    PipelineOptionsFactory.register(ExpandRecurringBillingEventsPipelineOptions.class);
    ExpandRecurringBillingEventsPipelineOptions options =
        PipelineOptionsFactory.fromArgs(args)
            .withValidation()
            .as(ExpandRecurringBillingEventsPipelineOptions.class);
    // Hardcode the transaction level to be at serializable we do not want concurrent runs of the
    // pipeline for the same window to create duplicate OneTimes. This ensures that the set of
    // existing OneTimes do not change by the time new OneTimes are inserted within a transaction.
    //
    // Per PostgreSQL, serializable isolation level does not introduce any blocking beyond that
    // present in  repeatable read other than some overhead related to monitoring possible
    // serializable anomalies. Therefore, in most cases, since each worker of the same job works on
    // a different set of recurrings, it is not possible for their execution order to affect
    // serialization outcome, and the performance penalty should be minimum when using serializable
    // compared to using repeatable read.
    //
    // We should pay some attention to the runtime of the job and logs when we run this job daily on
    // production to check the actual performance impact for using this isolation level (i.e. check
    // the frequency of occurrence of retried transactions due to serialization errors) to assess
    // the actual parallelism of the job.
    //
    // See: https://www.postgresql.org/docs/current/transaction-iso.html
    options.setIsolationOverride(TransactionIsolationLevel.TRANSACTION_SERIALIZABLE);
    Pipeline pipeline = Pipeline.create(options);
    new ExpandRecurringBillingEventsPipeline(options, new SystemClock()).run(pipeline);
  }

  @Singleton
  @Component(
      modules = {CustomLogicModule.class, CustomLogicFactoryModule.class, ConfigModule.class})
  interface PipelineComponent {

    DomainPricingLogic domainPricingLogic();

    @Config("jdbcBatchSize")
    int batchSize();
  }
}
