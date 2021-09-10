// Copyright 2017 The Nomulus Authors. All Rights Reserved.
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

package google.registry.model.rde;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import org.joda.time.DateTime;
import org.joda.time.format.DateTimeFormatter;
import org.joda.time.format.ISODateTimeFormat;

/** Utility class for generating RDE filenames and string IDs. */
public final class RdeNamingUtils {

  private static final DateTimeFormatter DATE_FORMATTER = ISODateTimeFormat.date();

  /**
   * Returns extensionless RDE filename in format {@code <gTLD>_<YYYY-MM-DD>_<type>_S<#>_R<rev>}.
   *
   * <p>This naming scheme is defined in the {@code gTLD_Applicant_Guidebook_full.pdf}.
   */
  public static
      String makeRydeFilename(String tld, DateTime date, RdeMode mode, int series, int revision) {
    checkArgument(series >= 1, "series >= 1");
    checkArgument(revision >= 0, "revision >= 0");
    return String.format("%s_S%d_R%d", makePartialName(tld, date, mode), series, revision);
  }

  /** Returns same thing as {@link #makeRydeFilename} except without the series and revision. */
  public static String makePartialName(String tld, DateTime date, RdeMode mode) {
    return String.format("%s_%s_%s",
        checkNotNull(tld), formatDate(date), mode.getFilenameComponent());
  }

  /** Returns date as a hyphened string with ISO-8601 ordering, e.g. {@code 1984-12-18}. */
  private static String formatDate(DateTime date) {
    checkArgument(date.withTimeAtStartOfDay().equals(date), "Not midnight: %s", date);
    return DATE_FORMATTER.print(date);
  }

  private RdeNamingUtils() {}
}
