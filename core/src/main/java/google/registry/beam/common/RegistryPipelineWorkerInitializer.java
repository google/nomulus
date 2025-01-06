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

package google.registry.beam.common;

import static google.registry.beam.common.RegistryPipelineOptions.toRegistryPipelineComponent;

import com.google.auto.service.AutoService;
import com.google.common.flogger.FluentLogger;
import dagger.Lazy;
import google.registry.persistence.transaction.JpaTransactionManager;
import google.registry.persistence.transaction.TransactionManagerFactory;
import google.registry.util.RegistryEnvironment;
import google.registry.util.SystemPropertySetter;
import org.apache.beam.sdk.harness.JvmInitializer;
import org.apache.beam.sdk.options.PipelineOptions;

/**
 * Sets up Nomulus environment and initializes JPA on each pipeline worker. It is assumed that the
 * pipeline only works with one SQL database.
 *
 * <p>This class only takes effect in portable beam pipeline runners (including the Cloud Dataflow
 * runner). It is not invoked in test pipelines.
 */
@AutoService(JvmInitializer.class)
public class RegistryPipelineWorkerInitializer implements JvmInitializer {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();
  public static final String PROPERTY = "google.registry.beam";

  @Override
  public void beforeProcessing(PipelineOptions options) {
    RegistryPipelineOptions registryOptions = options.as(RegistryPipelineOptions.class);
    RegistryEnvironment environment = registryOptions.getRegistryEnvironment();
    if (environment == null || environment.equals(RegistryEnvironment.UNITTEST)) {
      throw new RuntimeException(
          "A registry environment must be specified in the pipeline options.");
    }
    logger.atInfo().log("Setting up RegistryEnvironment %s.", environment);
    environment.setup();
    RegistryPipelineComponent registryPipelineComponent =
        toRegistryPipelineComponent(registryOptions);
    Lazy<JpaTransactionManager> transactionManagerLazy =
        switch (registryOptions.getJpaTransactionManagerType()) {
          case READ_ONLY_REPLICA ->
              registryPipelineComponent.getReadOnlyReplicaJpaTransactionManager();
          case REGULAR -> registryPipelineComponent.getJpaTransactionManager();
          default ->
              throw new IllegalStateException(
                  String.format(
                      "Unknown JPA transaction manager type: %s",
                      registryOptions.getJpaTransactionManagerType()));
        };
    TransactionManagerFactory.setJpaTmOnBeamWorker(transactionManagerLazy::get);
    SystemPropertySetter.PRODUCTION_IMPL.setProperty(PROPERTY, "true");
  }
}
