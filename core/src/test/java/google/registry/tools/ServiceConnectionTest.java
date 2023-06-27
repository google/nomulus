// Copyright 2023 The Nomulus Authors. All Rights Reserved.
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

package google.registry.tools;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.request.Action.Service.DEFAULT;

import org.junit.jupiter.api.Test;

/** Unit tests for {@link google.registry.tools.ServiceConnection}. */
public class ServiceConnectionTest {

  @Test
  void testServerUrl_notCanary() {
    ServiceConnection connection = new ServiceConnection().withService(DEFAULT, false);
    String serverUrl = connection.getServer().toString();
    assertThat(serverUrl).isEqualTo("https://default.example.com"); // See default-config.yaml
  }

  @Test
  void testServerUrl_canary() {
    ServiceConnection connection = new ServiceConnection().withService(DEFAULT, true);
    String serverUrl = connection.getServer().toString();
    assertThat(serverUrl).isEqualTo("https://nomulus-dot-default.example.com");
  }
}
