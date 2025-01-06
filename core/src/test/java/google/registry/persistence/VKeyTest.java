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
package google.registry.persistence;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import google.registry.model.domain.Domain;
import google.registry.model.poll.PollMessage;
import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.persistence.transaction.JpaTestExtensions.JpaIntegrationTestExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link VKey}. */
class VKeyTest {

  @RegisterExtension
  final JpaIntegrationTestExtension jpa =
      new JpaTestExtensions.Builder().buildIntegrationTestExtension();

  @Test
  void testSuccess_createWithLongKey() {
    VKey<PollMessage> key = VKey.create(PollMessage.class, 5L);
    assertThat(key.getKey()).isEqualTo(5L);
    assertThat(key.getKind()).isEqualTo(PollMessage.class);
  }

  @Test
  void testSuccess_createWithStringKey() {
    VKey<Domain> key = VKey.create(Domain.class, "blah");
    assertThat(key.getKey()).isEqualTo("blah");
    assertThat(key.getKind()).isEqualTo(Domain.class);
  }

  @Test
  void testFailure_missingArguments() {
    assertThat(assertThrows(IllegalArgumentException.class, () -> VKey.create(null, "blah")))
        .hasMessageThat()
        .isEqualTo("kind must not be null");
    assertThat(assertThrows(IllegalArgumentException.class, () -> VKey.create(Domain.class, null)))
        .hasMessageThat()
        .isEqualTo("key must not be null");
  }

  @Test
  void testSuccess_createFromString() {
    VKey<Domain> key = VKey.createEppVKeyFromString("kind:Domain@sql:rO0ABXQABGJsYWg");
    assertThat(key.getKey()).isEqualTo("blah");
    assertThat(key.getKind()).isEqualTo(Domain.class);
  }

  @Test
  void testFailure_createFromString_missingKind() {
    assertThat(
            assertThrows(
                IllegalArgumentException.class,
                () -> VKey.createEppVKeyFromString("sql:rO0ABXQABGJsYWg")))
        .hasMessageThat()
        .isEqualTo("\"kind\" missing from the string: sql:rO0ABXQABGJsYWg");
  }

  @Test
  void testFailure_createFromString_missingKey() {
    assertThat(
            assertThrows(
                IllegalArgumentException.class, () -> VKey.createEppVKeyFromString("kind:Domain")))
        .hasMessageThat()
        .isEqualTo("\"sql\" missing from the string: kind:Domain");
  }

  @Test
  void testFailure_createFromString_wrongKind() {
    assertThat(
            assertThrows(
                IllegalArgumentException.class,
                () -> VKey.createEppVKeyFromString("kind:Registry@sql:rO0ABXQABGJsYWg")))
        .hasMessageThat()
        .isEqualTo("Registry is not an EppResource");
  }

  @Test
  void testSuccess_stringify() {
    VKey<Domain> key = VKey.create(Domain.class, "blah");
    String keyString = key.stringify();
    assertThat(keyString).isEqualTo("kind:Domain@sql:rO0ABXQABGJsYWg");
  }

  @Test
  void testSuccess_toString() {
    VKey<Domain> key = VKey.create(Domain.class, "blah");
    String keyString = key.toString();
    assertThat(keyString).isEqualTo("VKey<Domain>(sql:blah)");
  }
}
