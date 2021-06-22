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

package google.registry.beam.common;

import static com.google.common.base.Verify.verify;
import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;

import google.registry.backup.AppEngineEnvironment;
import google.registry.model.contact.ContactResource;
import google.registry.persistence.transaction.CriteriaQueryBuilder;
import google.registry.persistence.transaction.JpaTransactionManager;
import java.io.Serializable;
import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.metrics.Counter;
import org.apache.beam.sdk.metrics.Metrics;
import org.apache.beam.sdk.options.PipelineOptionsFactory;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.ParDo;

/** Toy pipeline that demonstrates how to use {@link JpaTransactionManager} in BEAM pipelines. */
public class JpaDemoPipeline implements Serializable {

  public static void main(String[] args) {
    RegistryPipelineOptions options =
        PipelineOptionsFactory.fromArgs(args).withValidation().as(RegistryPipelineOptions.class);
    RegistryPipelineOptions.validateRegistryPipelineOptions(options);

    Pipeline pipeline = Pipeline.create(options);
    pipeline
        .apply(
            "Read contacts",
            RegistryJpaIO.read(
                () -> CriteriaQueryBuilder.create(ContactResource.class).build(),
                ContactResource::getRepoId))
        .apply(
            "Count Contacts",
            ParDo.of(
                new DoFn<String, Void>() {
                  private Counter counter = Metrics.counter("Contacts", "Read");

                  @ProcessElement
                  public void processElement() {
                    // AppEngineEnvironment is needed as long as JPA entity classes still depends
                    // on Objectify.
                    try (AppEngineEnvironment allowOfyEntity = new AppEngineEnvironment()) {
                      int result =
                          (Integer)
                              jpaTm()
                                  .transact(
                                      () ->
                                          jpaTm()
                                              .getEntityManager()
                                              .createNativeQuery("select 1;")
                                              .getSingleResult());
                      verify(result == 1, "Expecting 1, got %s.", result);
                    }
                    counter.inc();
                  }
                }));

    pipeline.run();
  }
}
