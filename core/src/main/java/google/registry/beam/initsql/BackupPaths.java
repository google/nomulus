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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Strings.isNullOrEmpty;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Streams;
import org.joda.time.DateTime;

/**
 * Helpers for determining the fully qualified paths to Nomulus backup files. A backup consists of a
 * Datastore export and Nomulus CommitLogs that overlap with the export.
 */
public final class BackupPaths {

  private BackupPaths() {}

  private static final String WILDCARD_CHAR = "*";
  private static final String EXPORT_PATTERN_TEMPLATE = "%s/all_namespaces/kind_%s/output-%s";

  public static final String COMMIT_LOG_NAME_PREFIX = "commit_diff_until_";
  private static final String COMMIT_LOG_PATTERN_TEMPLATE = "%s/" + COMMIT_LOG_NAME_PREFIX + "*";

  /**
   * Pattern of the per-project file with Cloud SQL connection information. To get a concrete path,
   * user needs to provide the name of the environment, alpha, crash, sandbox, or production. This
   * file is meant for applications without access to secrets stored in Datastore.
   *
   * <p>In production, this is an base-64 encoded encrypted file with one line, which contains
   * space-separated values of Cloud SQL instance name, login, and password.
   *
   * <p>A plain text may be used for tests to a local database. Replace Cloud SQL instance name with
   * JDBC URL.
   */
  private static final String SQL_CONN_INFO_FILE_PATTERN =
      "gs://domain-registry-dev-deploy/cloudsql-credentials/%s/admin_credential.enc";

  private static final ImmutableSet<String> ALLOWED_ENV =
      ImmutableSet.of("alpha", "crash", "sandbox", "production");

  /**
   * Returns a regex pattern that matches all Datastore export files of a given {@code kind}.
   *
   * @param exportDir path to the top directory of a Datastore export
   * @param kind the 'kind' of the Datastore entity
   */
  public static String getExportFileNamePattern(String exportDir, String kind) {
    checkArgument(!isNullOrEmpty(exportDir), "Null or empty exportDir.");
    checkArgument(!isNullOrEmpty(kind), "Null or empty kind.");
    return String.format(EXPORT_PATTERN_TEMPLATE, exportDir, kind, WILDCARD_CHAR);
  }

  /**
   * Returns an {@link ImmutableList} of regex patterns that match all Datastore export files of the
   * given {@code kinds}.
   *
   * @param exportDir path to the top directory of a Datastore export
   * @param kinds all entity 'kinds' to be matched
   */
  public static ImmutableList<String> getExportFilePatterns(
      String exportDir, Iterable<String> kinds) {
    checkNotNull(kinds, "kinds");
    return Streams.stream(kinds)
        .map(kind -> getExportFileNamePattern(exportDir, kind))
        .collect(ImmutableList.toImmutableList());
  }

  /**
   * Returns the fully qualified path of a Datastore export file with the given {@code kind} and
   * {@code shard}.
   *
   * @param exportDir path to the top directory of a Datastore export
   * @param kind the 'kind' of the Datastore entity
   * @param shard an integer suffix of the file name
   */
  public static String getExportFileNameByShard(String exportDir, String kind, int shard) {
    checkArgument(!isNullOrEmpty(exportDir), "Null or empty exportDir.");
    checkArgument(!isNullOrEmpty(kind), "Null or empty kind.");
    checkArgument(shard >= 0, "Negative shard %s not allowed.", shard);
    return String.format(EXPORT_PATTERN_TEMPLATE, exportDir, kind, Integer.toString(shard));
  }

  /** Returns an {@link ImmutableList} of regex patterns that match all CommitLog files. */
  public static ImmutableList<String> getCommitLogFilePatterns(String commitLogDir) {
    return ImmutableList.of(String.format(COMMIT_LOG_PATTERN_TEMPLATE, commitLogDir));
  }

  /** Gets the Commit timestamp from a CommitLog file name. */
  public static DateTime getCommitLogTimestamp(String fileName) {
    checkArgument(!isNullOrEmpty(fileName), "Null or empty fileName.");
    int start = fileName.lastIndexOf(COMMIT_LOG_NAME_PREFIX);
    checkArgument(start >= 0, "Illegal file name %s.", fileName);
    return DateTime.parse(fileName.substring(start + COMMIT_LOG_NAME_PREFIX.length()));
  }

  public static ImmutableList<String> getCloudSQLCredentialFilePatterns(String environmentName) {
    checkArgument(
        ALLOWED_ENV.contains(environmentName), "Invalid environment name %s", environmentName);
    return ImmutableList.of(String.format(SQL_CONN_INFO_FILE_PATTERN, environmentName));
  }
}
