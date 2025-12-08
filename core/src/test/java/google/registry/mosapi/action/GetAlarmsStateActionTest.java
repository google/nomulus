// Copyright 2025 The Nomulus Authors. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package google.registry.mosapi.action;

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableList;
import com.google.common.net.MediaType;
import com.google.gson.Gson;
import google.registry.mosapi.dto.servicemonitoring.AlarmResponse;
import google.registry.mosapi.dto.servicemonitoring.AlarmStatus;
import google.registry.mosapi.services.MosApiAlarmService;
import google.registry.testing.FakeResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Unit tests for {@link GetAlarmsStateAction}. */
@ExtendWith(MockitoExtension.class)
public class GetAlarmsStateActionTest {
  @Mock private MosApiAlarmService alarmService;
  private final FakeResponse response = new FakeResponse();
  private final Gson gson = new Gson();
  private GetAlarmsStateAction action;

  @BeforeEach
  void beforeEach() {
    action = new GetAlarmsStateAction(alarmService, response, gson);
  }

  @Test
  void testRun_returnsAlarmData() {
    // Create actual objects rather than Maps to avoid ClassCastException
    AlarmStatus status = new AlarmStatus("example", "dns", "Up", null);
    AlarmResponse alarmResponse = new AlarmResponse(ImmutableList.of(status));

    when(alarmService.checkAllAlarms()).thenReturn(alarmResponse);

    action.run();

    assertThat(response.getContentType()).isEqualTo(MediaType.JSON_UTF_8);
    // Validate JSON contains expected fields
    String payload = response.getPayload();
    assertThat(payload).contains("\"service\":\"dns\"");
    assertThat(payload).contains("\"status\":\"Up\"");
    verify(alarmService).checkAllAlarms();
  }
}
