// Copyright 2025 The Nomulus Authors. All Rights Reserved.
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

package google.registry.beam.wipeout;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static org.apache.beam.sdk.values.TypeDescriptors.voids;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Streams;
import google.registry.beam.common.RegistryJpaIO;
import google.registry.model.contact.ContactHistory;
import google.registry.model.reporting.HistoryEntry.HistoryEntryId;
import google.registry.persistence.PersistenceModule.TransactionIsolationLevel;
import google.registry.persistence.VKey;
import java.io.Serializable;
import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.PipelineResult;
import org.apache.beam.sdk.coders.KvCoder;
import org.apache.beam.sdk.coders.StringUtf8Coder;
import org.apache.beam.sdk.coders.VarLongCoder;
import org.apache.beam.sdk.metrics.Counter;
import org.apache.beam.sdk.metrics.Metrics;
import org.apache.beam.sdk.options.PipelineOptionsFactory;
import org.apache.beam.sdk.transforms.MapElements;
import org.apache.beam.sdk.transforms.join.CoGroupByKey;
import org.apache.beam.sdk.transforms.join.KeyedPCollectionTuple;
import org.apache.beam.sdk.values.KV;
import org.apache.beam.sdk.values.PCollection;
import org.apache.beam.sdk.values.TupleTag;
import org.joda.time.DateTime;

/**
 * Definition of a Dataflow Flex pipeline template, which finds out {@link ContactHistory} entries
 * that are older than a given age (excluding the most recent one, even if it falls with the range)
 * and wipe out PII information in them.
 *
 * <p>To stage this template locally, run {@code ./nom_build :core:sBP --environment=alpha \
 * --pipeline=wipeOutContactHistoryPii}.
 *
 * <p>Then, you can run the staged template via the API client library, gCloud or a raw REST call.
 */
public class WipeOutContactHistoryPiiPipeline implements Serializable {

  private static final long serialVersionUID = -4111052675715913820L;
  private static final TupleTag<Long> REVISIONS_TO_WIPE = new TupleTag<>();
  private static final TupleTag<Long> MOST_RECENT_REVISION = new TupleTag<>();

  private final DateTime cutoffTime;
  private final boolean dryRun;
  private final Counter contactsInScope =
      Metrics.counter("WipeOutContactHistoryPii", "contacts in scope");
  private final Counter historiesToWipe =
      Metrics.counter("WipeOutContactHistoryPii", "contact histories to wipe PII from");
  private final Counter historiesWiped =
      Metrics.counter("WipeOutContactHistoryPii", "contact histories actually updated");

  WipeOutContactHistoryPiiPipeline(WipeOutContactHistoryPiiPipelineOptions options) {
    dryRun = options.getIsDryRun();
    cutoffTime = DateTime.parse(options.getCutoffTime());
  }

  void setup(Pipeline pipeline) {
    KeyedPCollectionTuple.of(REVISIONS_TO_WIPE, getHistoryEntriesToWipe(pipeline))
        .and(MOST_RECENT_REVISION, getMostRecentHistoryEntries(pipeline))
        .apply("Group by contact", CoGroupByKey.create())
        .apply(
            "Wipe out PII",
            MapElements.into(voids())
                .via(
                    kv -> {
                      String repoId = kv.getKey();
                      long mostRecentRevision = kv.getValue().getOnly(MOST_RECENT_REVISION);
                      ImmutableList<Long> revisionsToWipe =
                          Streams.stream(kv.getValue().getAll(REVISIONS_TO_WIPE))
                              .filter(e -> e != mostRecentRevision)
                              .collect(toImmutableList());
                      if (revisionsToWipe.isEmpty()) {
                        return null;
                      }
                      contactsInScope.inc();
                      tm().transact(
                              () -> {
                                for (long revisionId : revisionsToWipe) {
                                  historiesToWipe.inc();
                                  ContactHistory history =
                                      tm().loadByKey(
                                              VKey.create(
                                                  ContactHistory.class,
                                                  new HistoryEntryId(repoId, revisionId)));
                                  // In the unlikely case where multiple pipelines run at the
                                  // same time, or where the runner decides to rerun a particular
                                  // transform, we might have a history entry that has already been
                                  // wiped at this point. There's no need to wipe it again.
                                  if (!dryRun
                                      && history.getContactBase().isPresent()
                                      && history.getContactBase().get().getEmailAddress() != null) {
                                    historiesWiped.inc();
                                    tm().update(history.asBuilder().wipeOutPii().build());
                                  }
                                }
                              });
                      return null;
                    }));
  }

  PCollection<KV<String, Long>> getHistoryEntriesToWipe(Pipeline pipeline) {
    return pipeline.apply(
        "Find contact histories to wipee",
        // Email is one of the required fields in EPP, meaning it's initially not null when it
        // is set by EPP flows (even though it is nullalbe in the SQL schema). Therefore,
        // checking if it's null is one way to avoid processing contact history entities that
        // have been processed previously. Refer to RFC 5733 for more information.
        RegistryJpaIO.read(
                "SELECT repoId, revisionId FROM ContactHistory WHERE resource.email IS NOT NULL"
                    + " AND modificationTime < :cutoffTime",
                ImmutableMap.of("cutoffTime", cutoffTime),
                Object[].class,
                row -> KV.of((String) row[0], (long) row[1]))
            .withCoder(KvCoder.of(StringUtf8Coder.of(), VarLongCoder.of())));
  }

  PCollection<KV<String, Long>> getMostRecentHistoryEntries(Pipeline pipeline) {
    return pipeline.apply(
        "Find the most recent historiy entry for each contact",
        RegistryJpaIO.read(
                "SELECT repoId, revisionId FROM ContactHistory"
                    + " WHERE (repoId, modificationTime) IN"
                    + " (SELECT repoId, MAX(modificationTime) FROM ContactHistory GROUP BY repoId)",
                ImmutableMap.of(),
                Object[].class,
                row -> KV.of((String) row[0], (long) row[1]))
            .withCoder(KvCoder.of(StringUtf8Coder.of(), VarLongCoder.of())));
  }

  PipelineResult run(Pipeline pipeline) {
    setup(pipeline);
    return pipeline.run();
  }

  public static void main(String[] args) {
    PipelineOptionsFactory.register(WipeOutContactHistoryPiiPipelineOptions.class);
    WipeOutContactHistoryPiiPipelineOptions options =
        PipelineOptionsFactory.fromArgs(args)
            .withValidation()
            .as(WipeOutContactHistoryPiiPipelineOptions.class);
    // Repeatable read should be more than enough since we are dealing with old history entries that
    // are otherwise immutable.
    options.setIsolationOverride(TransactionIsolationLevel.TRANSACTION_REPEATABLE_READ);
    Pipeline pipeline = Pipeline.create(options);
    new WipeOutContactHistoryPiiPipeline(options).run(pipeline);
  }
}
