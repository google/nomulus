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

package google.registry.beam.comparedb;

import google.registry.model.annotations.DeleteAfterMigration;
import javax.annotation.Nullable;
import org.apache.beam.sdk.options.Description;
import org.apache.beam.sdk.options.Validation;

/** BEAM pipeline options for {@link ValidateDatastorePipelineOptions}. */
@DeleteAfterMigration
public interface ValidateDatastorePipelineOptions extends ValidateSqlPipelineOptions {

  @Description(
      "The id of the SQL snapshot to be compared with Datastore. "
          + "If null, the current state of the SQL database is used.")
  @Nullable
  String getSqlSnapshotId();

  void setSqlSnapshotId(String snapshotId);

  @Description("The latest CommitLogs to load, in ISO8601 format.")
  @Validation.Required
  String getLatestCommitLogTimestamp();

  void setLatestCommitLogTimestamp(String commitLogEndTimestamp);
}
