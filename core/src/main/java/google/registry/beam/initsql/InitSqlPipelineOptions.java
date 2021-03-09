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

import google.registry.beam.common.RegistryPipelineOptions;
import org.apache.beam.sdk.options.Description;
import org.apache.beam.sdk.options.Validation;

/** Pipeline options for {@link InitSqlPipeline} */
public interface InitSqlPipelineOptions extends RegistryPipelineOptions {

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
}
