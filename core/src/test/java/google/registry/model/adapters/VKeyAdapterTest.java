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

package google.registry.model.adapters;

import static com.google.common.truth.Truth.assertThat;

import com.google.gson.Gson;
import google.registry.model.billing.BillingEvent;
import google.registry.model.domain.Domain;
import google.registry.persistence.VKey;
import google.registry.tools.GsonUtils;
import org.junit.jupiter.api.Test;

/** Tests for {@link ClassTypeAdapter} and {@link SerializableJsonTypeAdapter}. */
public class VKeyAdapterTest {

  private static final Gson GSON = GsonUtils.provideGson();

  @Test
  void testVKeyConversion_string() {
    VKey<Domain> vkey = VKey.create(Domain.class, "someRepoId");
    String vkeyJson = GSON.toJson(vkey);
    assertThat(vkeyJson)
        .isEqualTo(
            "{\"key\":\"someRepoId\",\"kind\":" + "\"google.registry.model.domain.Domain\"}");
    assertThat(GSON.fromJson(vkeyJson, VKey.class)).isEqualTo(vkey);
  }

  @Test
  void testVKeyConversion_number() {
    VKey<BillingEvent> vkey = VKey.create(BillingEvent.class, 203L);
    String vkeyJson = GSON.toJson(vkey);
    assertThat(vkeyJson)
        .isEqualTo("{\"key\":203,\"kind\":" + "\"google.registry.model.billing.BillingEvent\"}");
    assertThat(GSON.fromJson(vkeyJson, VKey.class)).isEqualTo(vkey);
  }
}
