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

package google.registry.reporting.icann;

import com.google.common.io.Resources;
import google.registry.util.ResourceUtils;
import java.net.URL;
import org.joda.time.YearMonth;
import org.joda.time.format.DateTimeFormat;

final class QueryBuilderUtils {

  private QueryBuilderUtils() {}

  /** Returns the table name of the query, suffixed with the yearMonth in _yyyyMM format. */
  static String getTableName(String queryName, YearMonth yearMonth) {
    return String.format("%s_%s", queryName, DateTimeFormat.forPattern("yyyyMM").print(yearMonth));
  }

  /** Returns {@link String} for file in {@code reporting/sql/} directory. */
  static String getQueryFromFile(String filename) {
    return ResourceUtils.readResourceUtf8(getUrl(filename));
  }

  private static URL getUrl(String filename) {
    return Resources.getResource(QueryBuilderUtils.class, "sql/" + filename);
  }
}
