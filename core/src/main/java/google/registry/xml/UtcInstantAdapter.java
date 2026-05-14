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

package google.registry.xml;

import static com.google.common.base.Strings.isNullOrEmpty;
import static google.registry.util.DateTimeUtils.formatInstant;
import static google.registry.util.DateTimeUtils.parseInstant;

import jakarta.xml.bind.annotation.adapters.XmlAdapter;
import java.time.Instant;
import javax.annotation.CheckForNull;
import javax.annotation.Nullable;

/**
 * Adapter to use an {@link Instant} when marshalling XML timestamps.
 *
 * <p>These fields shall contain timestamps indicating the date and time in UTC as specified in
 * RFC3339.
 *
 * @see <a href="http://tools.ietf.org/html/rfc3339">RFC3339</a>
 */
public class UtcInstantAdapter extends XmlAdapter<String, Instant> {

  public static String getFormattedString(@Nullable Instant timestamp) {
    return (timestamp == null) ? "" : formatInstant(timestamp);
  }

  /**
   * Parses an ISO timestamp string into a UTC {@link Instant} object, converting timezones if
   * necessary. If {@code timestamp} is empty or {@code null} then {@code null} is returned.
   */
  @Override
  @CheckForNull
  public Instant unmarshal(@Nullable String timestamp) {
    if (isNullOrEmpty(timestamp)) {
      return null;
    }
    return parseInstant(timestamp);
  }

  /**
   * Converts {@link Instant} to UTC and returns it as an RFC3339 string. If {@code timestamp} is
   * {@code null} then an empty string is returned.
   */
  @Override
  public String marshal(@Nullable Instant timestamp) {
    return getFormattedString(timestamp);
  }
}
