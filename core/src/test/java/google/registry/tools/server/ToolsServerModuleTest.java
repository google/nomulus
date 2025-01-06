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

package google.registry.tools.server;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import google.registry.request.HttpException.BadRequestException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import org.joda.time.DateTime;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link ToolsServerModule}. */
public class ToolsServerModuleTest {

  private final HttpServletRequest request = mock(HttpServletRequest.class);

  @Test
  void test_provideDeletionTime() throws Exception {
    when(request.getParameter("activeOrDeletedSince")).thenReturn("1991-07-01T00:00:00Z");

    DateTime expected = DateTime.parse("1991-07-01T00:00:00Z");
    Optional<DateTime> dateTimeParam = ToolsServerModule.provideDeletionTime(request);

    assertThat(dateTimeParam).isEqualTo(Optional.of(expected));
  }

  @Test
  void test_doesNotprovideDeletionTimeOnEmptyParam() throws Exception {
    when(request.getParameter("activeOrDeletedSince")).thenReturn("");

    assertThat(ToolsServerModule.provideDeletionTime(request)).isEqualTo(Optional.empty());
  }

  @Test
  void test_provideDeletionTime_incorrectDateFormat_throwsBadRequestException() throws Exception {
    when(request.getParameter("activeOrDeletedSince")).thenReturn("error404?");

    BadRequestException thrown =
        assertThrows(
            BadRequestException.class, () -> ToolsServerModule.provideDeletionTime(request));
    assertThat(thrown).hasMessageThat().contains("Bad ISO 8601 timestamp: activeOrDeletedSince");
  }
}
