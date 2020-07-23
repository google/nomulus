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

package google.registry.reporting;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.common.truth.Truth8;
import google.registry.request.HttpException.BadRequestException;
import google.registry.testing.FakeClock;
import google.registry.util.Clock;
import java.util.Optional;
import javax.servlet.http.HttpServletRequest;
import org.joda.time.DateTime;
import org.joda.time.LocalDate;
import org.joda.time.YearMonth;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link ReportingModule}. */
class ReportingModuleTest {

  private HttpServletRequest req = mock(HttpServletRequest.class);
  private Clock clock;

  @BeforeEach
  void beforeEach() {
    clock = new FakeClock(DateTime.parse("2017-07-01TZ"));
  }

  @Test
  void testEmptyYearMonthParameter_returnsEmptyYearMonthOptional() {
    when(req.getParameter("yearMonth")).thenReturn("");
    Truth8.assertThat(ReportingModule.provideYearMonthOptional(req)).isEmpty();
  }

  @Test
  void testValidYearMonthParameter_returnsThatMonth() {
    when(req.getParameter("yearMonth")).thenReturn("2017-05");
    Truth8.assertThat(ReportingModule.provideYearMonthOptional(req))
        .hasValue(new YearMonth(2017, 5));
  }

  @Test
  void testInvalidYearMonthParameter_throwsException() {
    when(req.getParameter("yearMonth")).thenReturn("201705");
    BadRequestException thrown =
        assertThrows(
            BadRequestException.class, () -> ReportingModule.provideYearMonthOptional(req));
    assertThat(thrown)
        .hasMessageThat()
        .contains("yearMonth must be in yyyy-MM format, got 201705 instead");
  }

  @Test
  void testEmptyYearMonth_returnsLastMonth() {
    assertThat(ReportingModule.provideYearMonth(Optional.empty(), new LocalDate(2017, 1, 6)))
        .isEqualTo(new YearMonth(2016, 12));
  }

  @Test
  void testGivenYearMonth_returnsThatMonth() {
    assertThat(
            ReportingModule.provideYearMonth(
                Optional.of(new YearMonth(2017, 5)), new LocalDate(2017, 7, 6)))
        .isEqualTo(new YearMonth(2017, 5));
  }

  @Test
  void testEmptyDateParameter_returnsEmptyDateOptional() {
    when(req.getParameter("date")).thenReturn("");
    Truth8.assertThat(ReportingModule.provideDateOptional(req)).isEmpty();
  }

  @Test
  void testValidDateParameter_returnsThatDate() {
    when(req.getParameter("date")).thenReturn("2017-05-13");
    Truth8.assertThat(ReportingModule.provideDateOptional(req))
        .hasValue(new LocalDate(2017, 5, 13));
  }

  @Test
  void testInvalidDateParameter_throwsException() {
    when(req.getParameter("date")).thenReturn("20170513");
    BadRequestException thrown =
        assertThrows(BadRequestException.class, () -> ReportingModule.provideDateOptional(req));
    assertThat(thrown)
        .hasMessageThat()
        .contains("date must be in yyyy-MM-dd format, got 20170513 instead");
  }

  @Test
  void testEmptyDate_returnsToday() {
    when(req.getParameter("date")).thenReturn(null);
    assertThat(ReportingModule.provideDate(req, clock)).isEqualTo(new LocalDate(2017, 7, 1));
  }

  @Test
  void testGivenDate_returnsThatDate() {
    when(req.getParameter("date")).thenReturn("2017-07-02");
    assertThat(ReportingModule.provideDate(req, clock)).isEqualTo(new LocalDate(2017, 7, 2));
  }
}
