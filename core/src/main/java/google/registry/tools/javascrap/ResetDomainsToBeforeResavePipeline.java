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

package google.registry.tools.javascrap;

import static com.google.common.base.Preconditions.checkArgument;
import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;
import static google.registry.util.DateTimeUtils.isBeforeOrAt;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Streams;
import google.registry.beam.common.RegistryJpaIO;
import google.registry.beam.common.RegistryPipelineOptions;
import google.registry.config.RegistryEnvironment;
import google.registry.model.domain.Domain;
import google.registry.model.domain.DomainBase;
import google.registry.model.domain.DomainHistory;
import google.registry.model.reporting.HistoryEntryDao;
import google.registry.persistence.PersistenceModule.TransactionIsolationLevel;
import google.registry.persistence.VKey;
import java.io.Serializable;
import java.util.Optional;
import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.options.PipelineOptions;
import org.apache.beam.sdk.options.PipelineOptionsFactory;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.ParDo;
import org.joda.time.DateTime;

/**
 * Pipeline that resets domains to their state prior to the buggy pipeline that caused b/245940594.
 *
 * <p>The pipeline started at 2022-09-05T09:00:00.000Z. If the domain has any history objects before
 * that time, we reset the domain to the last one before that time. If not, we reset the domain to
 * the first time (the creation time). That way, it will be valid to replay all (and only)
 * DOMAIN_UPDATES after the pipeline start time.
 *
 * <p>We will not alter contacts or hosts -- this allows domains created post-2022-09-05 to maintain
 * their foreign key constraints, and it means that we won't need to replay any contact or host EPP
 * actions.
 *
 * <p>After resetting domains to 2022-09-05, we can replay only the DOMAIN_UPDATE EPP actions that
 * took place after that point, up until the first DomainHistory post-2022-09-10T12:00:00Z (the end
 * of the pipeline, roughly). At that point we can compare the fields of our domain object to the
 * domain object stored in that DomainHistory. If the fields are different, then that means that the
 * domain was likely affected by the overwrite bug.
 *
 * <p>To run the pipeline:
 *
 * <p><code>
 * $ ./nom_build :core:resetDomainsToBeforeResave --args="--region=us-central1
 *   --runner=DataflowRunner
 *   --registryEnvironment=QA
 *   --project={project-id}
 *   --workerMachineType=n2-standard-4"
 * </code>
 */
public class ResetDomainsToBeforeResavePipeline implements Serializable {

  private static final DateTime BAD_PIPELINE_START_TIME =
      DateTime.parse("2022-09-05T09:00:00.000Z");

  static void setup(Pipeline pipeline) {
    pipeline
        .apply(
            "Select all domain repo IDs",
            RegistryJpaIO.read(
                "SELECT d.repoId FROM Domain d", String.class, r -> VKey.create(Domain.class, r)))
        .apply("Reset each domain", ParDo.of(new DomainResetFunction()));
  }

  private static class DomainResetFunction extends DoFn<VKey<Domain>, Void> {

    @ProcessElement
    public void processElement(
        @Element VKey<Domain> key, PipelineOptions options, OutputReceiver<Void> outputReceiver) {
      jpaTm()
          .transact(
              () -> {
                // note: these are already sorted from earliest to latest
                ImmutableList<DomainHistory> allHistories =
                    HistoryEntryDao.loadHistoryObjectsForResource(key, DomainHistory.class);
                // If the domain was created before the pipeline, use the last history before it
                Optional<DomainBase> possibleDomainBeforePipeline =
                    Streams.findLast(
                        allHistories.stream()
                            .filter(
                                dh ->
                                    isBeforeOrAt(dh.getModificationTime(), BAD_PIPELINE_START_TIME))
                            .map(dh -> dh.getDomainBase().get()));
                // Otherwise (created after the pipeline), use the first (CREATE) history
                DomainBase domainToPersist =
                    possibleDomainBeforePipeline.orElseGet(
                        () -> allHistories.get(0).getDomainBase().get());
                jpaTm().put(new Domain.Builder().copyFrom(domainToPersist).build());
              });
    }
  }

  public static void main(String[] args) {
    RegistryPipelineOptions options =
        PipelineOptionsFactory.fromArgs(args).withValidation().as(RegistryPipelineOptions.class);
    checkArgument(
        options.getRegistryEnvironment().equals(RegistryEnvironment.QA),
        "This command is only meant to be run against the QA instance");
    RegistryPipelineOptions.validateRegistryPipelineOptions(options);
    options.setIsolationOverride(TransactionIsolationLevel.TRANSACTION_REPEATABLE_READ);

    Pipeline pipeline = Pipeline.create(options);
    setup(pipeline);
    pipeline.run();
  }
}
