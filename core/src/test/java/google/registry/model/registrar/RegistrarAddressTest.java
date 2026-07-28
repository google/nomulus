// Copyright 2026 The Nomulus Authors. All Rights Reserved.
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

package google.registry.model.registrar;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.common.collect.ImmutableList;
import google.registry.tools.GsonUtils;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link RegistrarAddress}. */
class RegistrarAddressTest {

  @Test
  void testSuccess_gsonInstantiation() {
    String json = "{\"street\":[\"123 W 14th St\"],\"city\":\"New York\",\"countryCode\":\"US\"}";
    RegistrarAddress address = GsonUtils.provideGson().fromJson(json, RegistrarAddress.class);
    assertThat(address.getStreet()).containsExactly("123 W 14th St");
    assertThat(address.getCity()).isEqualTo("New York");
    assertThat(address.getCountryCode()).isEqualTo("US");
  }

  @Test
  void testFailure_gsonInstantiation_missingStreet() {
    String json = "{\"city\":\"New York\",\"countryCode\":\"US\"}";
    assertThrows(
        NullPointerException.class,
        () -> GsonUtils.provideGson().fromJson(json, RegistrarAddress.class));
  }

  @Test
  void testFailure_gsonInstantiation_missingCity() {
    String json = "{\"street\":[\"123 W 14th St\"],\"countryCode\":\"US\"}";
    assertThrows(
        NullPointerException.class,
        () -> GsonUtils.provideGson().fromJson(json, RegistrarAddress.class));
  }

  @Test
  void testFailure_gsonInstantiation_missingCountryCode() {
    String json = "{\"street\":[\"123 W 14th St\"],\"city\":\"New York\"}";
    assertThrows(
        NullPointerException.class,
        () -> GsonUtils.provideGson().fromJson(json, RegistrarAddress.class));
  }

  @Test
  void testFailure_gsonInstantiation_invalidCountryCode() {
    String json = "{\"street\":[\"123 W 14th St\"],\"city\":\"New York\",\"countryCode\":\"USA\"}";
    assertThrows(
        IllegalArgumentException.class,
        () -> GsonUtils.provideGson().fromJson(json, RegistrarAddress.class));
  }

  @Test
  void testFailure_gsonInstantiation_tooManyStreetLines() {
    String json =
        "{\"street\":[\"1\",\"2\",\"3\",\"4\"],\"city\":\"New York\",\"countryCode\":\"US\"}";
    assertThrows(
        IllegalArgumentException.class,
        () -> GsonUtils.provideGson().fromJson(json, RegistrarAddress.class));
  }

  @Test
  void testSuccess_builder() {
    RegistrarAddress address =
        new RegistrarAddress.Builder()
            .setStreet(ImmutableList.of("123 W 14th St"))
            .setCity("New York")
            .setCountryCode("US")
            .build();
    assertThat(address.getStreet()).containsExactly("123 W 14th St");
  }

  @Test
  void testFailure_builder_missingStreet() {
    assertThrows(
        NullPointerException.class,
        () -> new RegistrarAddress.Builder().setCity("New York").setCountryCode("US").build());
  }
}
