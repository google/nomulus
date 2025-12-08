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

package google.registry.mosapi.dto.servicemonitoring;

import static com.google.common.truth.Truth.assertThat;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link AlarmStatus}. */
public class AlarmStatusTest {

  @Test
  void testAlarmStatus_properties() {
    String tld = "example";
    String service = "dns";
    String status = "Yes";
    String error = "Connection timeout";

    AlarmStatus alarmStatus = new AlarmStatus(tld, service, status, error);

    assertThat(alarmStatus.getTld()).isEqualTo(tld);
    assertThat(alarmStatus.getService()).isEqualTo(service);
    assertThat(alarmStatus.getStatus()).isEqualTo(status);
    assertThat(alarmStatus.getErrorMessage()).isEqualTo(error);
  }

  @Test
  void testAlarmStatus_nullableFields() {
    AlarmStatus alarmStatus = new AlarmStatus("example", "rdds", "No", null);

    assertThat(alarmStatus.getStatus()).isEqualTo("No");
    assertThat(alarmStatus.getErrorMessage()).isNull();
  }

  @Test
  void testJsonDeserialization() {
    String json =
        "{"
            + "\"tld\": \"example\","
            + "\"service\": \"dns\","
            + "\"status\": \"Yes\","
            + "\"errorMessage\": \"Error details\""
            + "}";

    Gson gson = new Gson();
    AlarmStatus alarmStatus = gson.fromJson(json, AlarmStatus.class);

    assertThat(alarmStatus.getTld()).isEqualTo("example");
    assertThat(alarmStatus.getService()).isEqualTo("dns");
    assertThat(alarmStatus.getStatus()).isEqualTo("Yes");
    assertThat(alarmStatus.getErrorMessage()).isEqualTo("Error details");
  }
}
