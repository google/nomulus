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

package google.registry.tools.params;

import static java.time.ZoneOffset.UTC;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * {@link Instant} CLI parameter converter/validator restricted to dates. The {@link Instant}s
 * produced by this class will always have a time zone of {@link ZoneOffset#UTC}.
 */
public final class DateParameter extends ParameterConverterValidator<Instant> {

  public DateParameter() {
    super("not an ISO-8601 date");
  }

  /**
   * Parser for Instants that permits only a restricted subset of ISO 8601 datetime syntax. The
   * supported format is "YYYY-MM-DD", i.e. there must only be a complete date.
   */
  private static final DateTimeFormatter STRICT_DATE_PARSER = DateTimeFormatter.ISO_LOCAL_DATE;

  @Override
  public Instant convert(String value) {
    return LocalDate.parse(value, STRICT_DATE_PARSER).atStartOfDay(UTC).toInstant();
  }
}
