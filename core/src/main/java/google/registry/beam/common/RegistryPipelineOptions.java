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

import google.registry.beam.common.RegistryJpaIO.Write;
import google.registry.config.RegistryEnvironment;
import google.registry.persistence.PersistenceModule.JpaTransactionManagerType;
import google.registry.persistence.PersistenceModule.TransactionIsolationLevel;
import java.util.Objects;
import javax.annotation.Nullable;
import org.apache.beam.sdk.extensions.gcp.options.GcpOptions;
import org.apache.beam.sdk.options.Default;
import org.apache.beam.sdk.options.Description;

/**
 * Defines Nomulus-specific pipeline options, e.g. JPA configurations.
 *
 * <p>When using the Cloud Dataflow runner, users are recommended to set an upper bound on active
 * database connections by setting the pipeline worker options including {@code --maxNumWorkers},
 * {@code workerMachineType}, and {@code numberOfWorkerHarnessThreads}. Please refer to {@link
 * Write#shards()} for more information.
 */
public interface RegistryPipelineOptions extends GcpOptions {

  @Description("The Registry environment.")
  RegistryEnvironment getRegistryEnvironment();

  void setRegistryEnvironment(RegistryEnvironment environment);

  @Description("The desired SQL transaction isolation level.")
  @Nullable
  TransactionIsolationLevel getIsolationOverride();

  void setIsolationOverride(TransactionIsolationLevel isolationOverride);

  @Description("The JPA Transaction Manager to use.")
  @Default.Enum(value = "REGULAR")
  JpaTransactionManagerType getJpaTransactionManagerType();

  void setJpaTransactionManagerType(JpaTransactionManagerType jpaTransactionManagerType);

  @Description("The number of entities to write to the SQL database in one operation.")
  @Default.Integer(20)
  int getSqlWriteBatchSize();

  void setSqlWriteBatchSize(int sqlWriteBatchSize);

  @Description(
      "Number of shards to create out of the data before writing to the SQL database. Please refer "
          + "to the Javadoc of RegistryJpaIO.Write.shards() for how to choose this value.")
  @Default.Integer(100)
  int getSqlWriteShards();

  void setSqlWriteShards(int maxConcurrentSqlWriters);

  static RegistryPipelineComponent toRegistryPipelineComponent(RegistryPipelineOptions options) {
    return DaggerRegistryPipelineComponent.builder()
        .isolationOverride(options.getIsolationOverride())
        .build();
  }

  /**
   * Validates the GCP project and Registry environment settings in {@code option}. If project is
   * undefined, it is set according to the Registry environment; if project is defined but
   * inconsistent with the Registry environment, an {@link IllegalArgumentException} will be thrown.
   *
   * <p>This method may modify the system property ("google.registry.environment" which is defined
   * in {@link RegistryEnvironment}). Tests calling this method must restore the original
   * environment on completion.
   */
  static void validateRegistryPipelineOptions(RegistryPipelineOptions options) {
    RegistryEnvironment environment = options.getRegistryEnvironment();
    if (environment == null) {
      return;
    }
    environment.setup();
    String projectByEnv = toRegistryPipelineComponent(options).getProjectId();
    if (Objects.equals(options.getProject(), projectByEnv)) {
      return;
    }
    if (options.getProject() == null) {
      options.setProject(projectByEnv);
      return;
    }
    throw new IllegalArgumentException(
        "Arguments for --project and --registryEnvironment do not match.");
  }
}
