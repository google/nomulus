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

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link MosApiRequestModule}. */
public class MosApiRequestModuleTest {
  // Explicitly create the mock to ensure it is not null
  private final HttpServletRequest req = mock(HttpServletRequest.class);

  @Test
  void provideDate_validDate_returnsOptionalDate() {
    when(req.getParameter("date")).thenReturn("2025-01-01");
    Optional<LocalDate> result = MosApiRequestModule.provideDate(req);
    assertThat(result).hasValue(LocalDate.of(2025, 1, 1));
  }

  @Test
  void provideDate_invalidFormat_returnsEmpty() {
    when(req.getParameter("date")).thenReturn("not-a-date");
    Optional<LocalDate> result = MosApiRequestModule.provideDate(req);
    assertThat(result).isEmpty();
  }

  @Test
  void provideDate_missingParameter_returnsEmpty() {
    when(req.getParameter("date")).thenReturn(null);
    Optional<LocalDate> result = MosApiRequestModule.provideDate(req);
    assertThat(result).isEmpty();
  }

  @Test
  void provideTld_present_returnsOptionalString() {
    when(req.getParameter("tld")).thenReturn("example");
    Optional<String> result = MosApiRequestModule.provideTld(req);
    assertThat(result).hasValue("example");
  }

  @Test
  void provideTld_missing_returnsEmpty() {
    when(req.getParameter("tld")).thenReturn("");
    Optional<String> result = MosApiRequestModule.provideTld(req);
    assertThat(result).isEmpty();
  }

  @Test
  void provideStartDate_validDate_returnsOptionalDate() {
    when(req.getParameter("startDate")).thenReturn("2025-05-01");
    Optional<LocalDate> result = MosApiRequestModule.provideStartDate(req);
    assertThat(result).hasValue(LocalDate.of(2025, 5, 1));
  }

  @Test
  void provideStartDate_invalidFormat_returnsEmpty() {
    when(req.getParameter("startDate")).thenReturn("2025/05/01"); // Wrong format
    Optional<LocalDate> result = MosApiRequestModule.provideStartDate(req);
    assertThat(result).isEmpty();
  }

  @Test
  void provideEndDate_validDate_returnsOptionalDate() {
    when(req.getParameter("endDate")).thenReturn("2025-12-31");
    Optional<LocalDate> result = MosApiRequestModule.provideEndDate(req);
    assertThat(result).hasValue(LocalDate.of(2025, 12, 31));
  }

  @Test
  void provideEndDate_missingParameter_returnsEmpty() {
    when(req.getParameter("endDate")).thenReturn(null);
    Optional<LocalDate> result = MosApiRequestModule.provideEndDate(req);
    assertThat(result).isEmpty();
  }
}
