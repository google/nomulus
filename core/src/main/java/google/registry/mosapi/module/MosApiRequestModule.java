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

package google.registry.mosapi.module;

import static google.registry.request.RequestParameters.extractOptionalParameter;

import dagger.Module;
import dagger.Provides;
import google.registry.request.Parameter;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Optional;

/** Dagger module for MoSAPI requests. */
@Module
public final class MosApiRequestModule {

  @Provides
  @Parameter("date")
  static Optional<LocalDate> provideDate(HttpServletRequest req) {
    Optional<String> dateString = extractOptionalParameter(req, "date");
    if (dateString.isEmpty()) {
      return Optional.empty();
    }
    try {
      return dateString.map(LocalDate::parse);
    } catch (DateTimeParseException e) {
      // For optional parameters, we can return empty on parse error.
      return Optional.empty();
    }
  }

  @Provides
  @Parameter("tld")
  static Optional<String> provideTld(HttpServletRequest req) {
    return extractOptionalParameter(req, "tld");
  }

  @Provides
  @Parameter("startDate")
  static Optional<LocalDate> provideStartDate(HttpServletRequest req) {
    Optional<String> dateString = extractOptionalParameter(req, "startDate");
    if (dateString.isEmpty()) {
      return Optional.empty();
    }
    try {
      return dateString.map(LocalDate::parse);
    } catch (DateTimeParseException e) {
      return Optional.empty();
    }
  }

  @Provides
  @Parameter("endDate")
  static Optional<LocalDate> provideEndDate(HttpServletRequest req) {
    Optional<String> dateString = extractOptionalParameter(req, "endDate");
    if (dateString.isEmpty()) {
      return Optional.empty();
    }
    try {
      return dateString.map(LocalDate::parse);
    } catch (DateTimeParseException e) {
      return Optional.empty();
    }
  }
}
