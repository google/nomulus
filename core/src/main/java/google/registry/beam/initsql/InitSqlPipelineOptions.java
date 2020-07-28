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

package google.registry.beam.initsql;

import javax.annotation.Nullable;
import org.apache.beam.sdk.extensions.gcp.options.GcpOptions;
import org.apache.beam.sdk.options.Default;
import org.apache.beam.sdk.options.Description;
import org.apache.beam.sdk.options.Validation;

/** Pipeline options for {@link InitSqlPipeline} */
public interface InitSqlPipelineOptions extends GcpOptions {

  @Description(
      "Overrides the URL to the SQL credential file. " + "Required if environment is not provided.")
  @Nullable
  String getSqlCredentialUrlOverride();

  void setSqlCredentialUrlOverride(String credentialUrlOverride);

  @Description("The root directory of the export to load.")
  String getDatastoreExportDir();

  void setDatastoreExportDir(String datastoreExportDir);

  @Description("The directory that contains all CommitLog files.")
  String getCommitLogDir();

  void setCommitLogDir(String commitLogDir);

  @Description("The earliest CommitLogs to load, in ISO8601 format.")
  @Validation.Required
  String getCommitLogStartTimestamp();

  void setCommitLogStartTimestamp(String commitLogStartTimestamp);

  @Description("The latest CommitLogs to load, in ISO8601 format.")
  @Validation.Required
  String getCommitLogEndTimestamp();

  void setCommitLogEndTimestamp(String commitLogEndTimestamp);

  @Description(
      "The deployed environment, alpha, crash, sandbox, or production. "
          + "Not required only if sqlCredentialUrlOverride is provided.")
  @Nullable
  String getEnvironment();

  void setEnvironment(String environment);

  @Description("The GCP project that contains the keyring used for decrypting the "
      + "SQL credential file.")
  @Nullable
  String getCloudKmsProjectId();

  void setCloudKmsProjectId(String cloudKmsProjectId);

  @Description(
      "The maximum JDBC connection pool size on a VM. "
          + "This value should be equal to or greater than the number of cores on the VM.")
  @Default.Integer(4)
  int getJdbcMaxPoolSize();

  void setJdbcMaxPoolSize(int jdbcMaxPoolSize);

  @Description(
      "A hint to the pipeline runner of the maximum number of concurrent SQL writers to create. "
          + "Note that multiple writers may run on the same VM and share the connection pool.")
  @Default.Integer(4)
  int getMaxConcurrentSqlWriters();

  void setMaxConcurrentSqlWriters(int maxConcurrentSqlWriters);

  @Description("The number of entities to be written to the SQL database in one transaction.")
  @Default.Integer(20)
  int getSqlWriteBatchSize();

  void setSqlWriteBatchSize(int sqlWriteBatchSize);
}
