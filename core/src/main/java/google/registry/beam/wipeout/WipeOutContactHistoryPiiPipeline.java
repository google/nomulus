// Copyright 2023 The Nomulus Authors. All Rights Reserved.
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

import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static org.apache.beam.sdk.values.TypeDescriptors.voids;

import com.google.common.collect.ImmutableMap;
import google.registry.beam.common.RegistryJpaIO;
import google.registry.model.contact.ContactHistory;
import google.registry.model.reporting.HistoryEntry.HistoryEntryId;
import google.registry.persistence.PersistenceModule.TransactionIsolationLevel;
import google.registry.persistence.VKey;
import java.io.Serializable;
import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.PipelineResult;
import org.apache.beam.sdk.coders.SerializableCoder;
import org.apache.beam.sdk.metrics.Counter;
import org.apache.beam.sdk.metrics.Metrics;
import org.apache.beam.sdk.options.PipelineOptionsFactory;
import org.apache.beam.sdk.transforms.MapElements;
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

  private final DateTime cutoffTime;
  private final boolean dryRun;
  private final Counter inScope =
      Metrics.counter("WipeOutContactHistoryPii", "Contact Histories to wipe PII from");
  private final Counter wipedOut =
      Metrics.counter("WipeOutContactHistoryPii", "Contact Histories actually updated");

  WipeOutContactHistoryPiiPipeline(WipeOutContactHistoryPiiPipelineOptions options) {
    dryRun = options.getIsDryRun();
    cutoffTime = DateTime.parse(options.getCutoffTime());
  }

  void setup(Pipeline pipeline) {
    pipeline
        .apply(
            "Find Contact Histories in scope",
            RegistryJpaIO.read(
                    "SELECT repoId, revisionId FROM ContactHistory  WHERE email IS NOT NULL AND"
                        + " modificationTime < :cutoffTime AND (repoId, modificationTime) not in"
                        + " (SELECT repoId, MAX(modificationTime) FROM ContactHistory GROUP BY"
                        + " repoId)",
                    ImmutableMap.of("cutoffTime", cutoffTime),
                    Object[].class,
                    row -> new HistoryEntryId((String) row[0], (long) row[1]))
                .withCoder(SerializableCoder.of(HistoryEntryId.class)))
        .apply(
            "Wipeout PII",
            MapElements.into(voids())
                .via(
                    historyEntryId -> {
                      inScope.inc();
                      tm().transact(
                              () -> {
                                ContactHistory history =
                                    tm().loadByKey(
                                            VKey.create(ContactHistory.class, historyEntryId));
                                // In the unlikely case where multiple pipelines run at the same
                                // time, or where the runner decides to rerun a particular
                                // transform, we might have a history entry that has already been
                                // wiped at this point. There's no need to wipe it again.
                                if (!dryRun
                                    && history.getContactBase().isPresent()
                                    && history.getContactBase().get().getEmailAddress() != null) {
                                  wipedOut.inc();
                                  tm().update(history.asBuilder().wipeOutPii().build());
                                }
                              });
                      return null;
                    }));
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
    options.setIsolationOverride(TransactionIsolationLevel.TRANSACTION_SERIALIZABLE);
    Pipeline pipeline = Pipeline.create(options);
    new WipeOutContactHistoryPiiPipeline(options).run(pipeline);
  }
}
