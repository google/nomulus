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

package google.registry.ui.server.registrar;

import static com.google.common.base.Strings.repeat;
import static com.google.common.truth.Truth.assertThat;
import static google.registry.testing.DatastoreHelper.loadRegistrar;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import google.registry.model.registrar.Registrar;
import google.registry.model.registrar.RegistrarAddress;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for security_settings.js use of {@link RegistrarSettingsAction}.
 *
 * <p>The default read and session validation tests are handled by the superclass.
 */
class WhoisSettingsTest extends RegistrarSettingsActionTestCase {

  @Test
  void testPost_update_success() throws Exception {
    Registrar modified =
        loadRegistrar(CLIENT_ID)
            .asBuilder()
            .setEmailAddress("hello.kitty@example.com")
            .setPhoneNumber("+1.2125650000")
            .setFaxNumber("+1.2125650001")
            .setUrl("http://acme.com/")
            .setWhoisServer("ns1.foo.bar")
            .setLocalizedAddress(
                new RegistrarAddress.Builder()
                    .setStreet(ImmutableList.of("76 Ninth Avenue", "Eleventh Floor"))
                    .setCity("New York")
                    .setState("NY")
                    .setZip("10009")
                    .setCountryCode("US")
                    .build())
            .build();
    Map<String, Object> response =
        action.handleJsonRequest(
            ImmutableMap.of("op", "update", "id", CLIENT_ID, "args", modified.toJsonMap()));
    assertThat(response.get("status")).isEqualTo("SUCCESS");
    assertThat(response.get("results")).isEqualTo(ImmutableList.of(modified.toJsonMap()));
    assertThat(loadRegistrar(CLIENT_ID)).isEqualTo(modified);
    assertMetric(CLIENT_ID, "update", "[OWNER]", "SUCCESS");
    verifyNotificationEmailsSent();
  }

  @Test
  void testPost_badUsStateCode_returnsFormFieldError() {
    Registrar modified =
        loadRegistrar(CLIENT_ID)
            .asBuilder()
            .setEmailAddress("hello.kitty@example.com")
            .setPhoneNumber("+1.2125650000")
            .setFaxNumber("+1.2125650001")
            .setLocalizedAddress(
                new RegistrarAddress.Builder()
                    .setStreet(ImmutableList.of("76 Ninth Avenue", "Eleventh Floor"))
                    .setCity("New York")
                    .setState("ZZ")
                    .setZip("10009")
                    .setCountryCode("US")
                    .build())
            .build();
    Map<String, Object> response =
        action.handleJsonRequest(
            ImmutableMap.of("op", "update", "id", CLIENT_ID, "args", modified.toJsonMap()));
    assertThat(response.get("status")).isEqualTo("ERROR");
    assertThat(response.get("field")).isEqualTo("localizedAddress.state");
    assertThat(response.get("message")).isEqualTo("Unknown US state code.");
    assertThat(loadRegistrar(CLIENT_ID)).isNotEqualTo(modified);
    assertMetric(CLIENT_ID, "update", "[OWNER]", "ERROR: FormFieldException");
  }

  @Test
  void testPost_badAddress_returnsFormFieldError() {
    Registrar modified =
        loadRegistrar(CLIENT_ID)
            .asBuilder()
            .setEmailAddress("hello.kitty@example.com")
            .setPhoneNumber("+1.2125650000")
            .setFaxNumber("+1.2125650001")
            .setLocalizedAddress(
                new RegistrarAddress.Builder()
                    .setStreet(ImmutableList.of("76 Ninth Avenue", repeat("lol", 200)))
                    .setCity("New York")
                    .setState("NY")
                    .setZip("10009")
                    .setCountryCode("US")
                    .build())
            .build();
    Map<String, Object> response =
        action.handleJsonRequest(
            ImmutableMap.of("op", "update", "id", CLIENT_ID, "args", modified.toJsonMap()));
    assertThat(response.get("status")).isEqualTo("ERROR");
    assertThat(response.get("field")).isEqualTo("localizedAddress.street[1]");
    assertThat((String) response.get("message"))
        .contains("Number of characters (600) not in range");
    assertThat(loadRegistrar(CLIENT_ID)).isNotEqualTo(modified);
    assertMetric(CLIENT_ID, "update", "[OWNER]", "ERROR: FormFieldException");
  }

  @Test
  void testPost_badWhoisServer_returnsFormFieldError() {
    Registrar modified =
        loadRegistrar(CLIENT_ID).asBuilder().setWhoisServer("tears@dry.tragical.lol").build();
    Map<String, Object> response =
        action.handleJsonRequest(
            ImmutableMap.of("op", "update", "id", CLIENT_ID, "args", modified.toJsonMap()));
    assertThat(response.get("status")).isEqualTo("ERROR");
    assertThat(response.get("field")).isEqualTo("whoisServer");
    assertThat(response.get("message")).isEqualTo("Not a valid hostname.");
    assertThat(loadRegistrar(CLIENT_ID)).isNotEqualTo(modified);
    assertMetric(CLIENT_ID, "update", "[OWNER]", "ERROR: FormFieldException");
  }
}
