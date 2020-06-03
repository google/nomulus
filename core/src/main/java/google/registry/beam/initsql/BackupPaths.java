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
import static com.google.common.base.Strings.isNullOrEmpty;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Helpers for determining the fully qualified paths to Nomulus backup files. A backup consists of a
 * Datastore export and Nomulus CommitLogs that overlap with the export.
 */
public final class BackupPaths {

  private BackupPaths() {}

  private static final String WILDCARD_CHAR = "*";
  private static final String EXPORT_PATTERN_TEMPLATE = "%s/all_namespaces/kind_%s/input-%s";
  /**
   * Regex pattern that captures the kind string in a file name. Datastore places no restrictions on
   * what characters may be used in a kind string.
   */
  private static final String FILENAME_TO_KIND_REGEX = ".+/all_namespaces/kind_(.+)/input-.+";

  private static final Pattern FILENAME_TO_KIND_PATTERN = Pattern.compile(FILENAME_TO_KIND_REGEX);

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

  /**
   * Returns the 'kind' of entity stored in a file based on the file name.
   *
   * <p>This method poses low risk and greatly simplifies the implementation of some transforms in
   * {@link ExportLoadingTransforms}.
   *
   * @see ExportLoadingTransforms
   */
  public static String getKindFromFileName(String fileName) {
    checkArgument(!isNullOrEmpty(fileName), "Null or empty fileName.");
    Matcher matcher = FILENAME_TO_KIND_PATTERN.matcher(fileName);
    checkArgument(
        matcher.matches(),
        "Illegal file name %s, should match %s.",
        fileName,
        FILENAME_TO_KIND_REGEX);
    return matcher.group(1);
  }
}
