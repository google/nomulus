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

package google.registry.model.smd;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.util.DateTimeUtils.START_INSTANT;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.common.collect.ImmutableMap;
import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.persistence.transaction.JpaTestExtensions.JpaIntegrationTestExtension;
import google.registry.testing.FakeClock;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link SignedMarkRevocationList}. */
public class SignedMarkRevocationListTest {

  @RegisterExtension
  final JpaIntegrationTestExtension jpa =
      new JpaTestExtensions.Builder().buildIntegrationTestExtension();

  private final FakeClock clock = new FakeClock(Instant.parse("2013-01-01T00:00:00Z"));

  @Test
  void testEmpty() {
    // When Cloud SQL is empty, it should give us an empty thing.
    assertThat(SignedMarkRevocationList.get())
        .isEqualTo(SignedMarkRevocationList.create(START_INSTANT, ImmutableMap.of()));
  }

  private SignedMarkRevocationList createSaveGetHelper(int rows) {
    ImmutableMap.Builder<String, Instant> revokes = new ImmutableMap.Builder<>();
    for (int i = 0; i < rows; i++) {
      revokes.put(Integer.toString(i), clock.now());
    }
    SignedMarkRevocationListDao.save(SignedMarkRevocationList.create(clock.now(), revokes.build()));
    SignedMarkRevocationList res = SignedMarkRevocationList.get();
    assertThat(res.size()).isEqualTo(rows);
    return res;
  }

  @Test
  void test_isSmdRevoked_null() {
    assertThrows(
        NullPointerException.class,
        () ->
            SignedMarkRevocationList.create(START_INSTANT, ImmutableMap.of())
                .isSmdRevoked(null, clock.now()));
  }

  @Test
  void test_isSmdRevoked_garbage() {
    SignedMarkRevocationList smdrl = createSaveGetHelper(100);
    assertThat(smdrl.getCreationTime()).isEqualTo(clock.now());
    assertThat(smdrl.isSmdRevoked("rofl", clock.now())).isFalse();
    assertThat(smdrl.isSmdRevoked("31337", clock.now())).isFalse();
  }

  @Test
  void test_getCreationTime() {
    clock.setTo(Instant.parse("2000-01-01T00:00:00Z"));
    createSaveGetHelper(5);
    assertThat(SignedMarkRevocationList.get().getCreationTime())
        .isEqualTo(Instant.parse("2000-01-01T00:00:00Z"));
    clock.advanceBy(Duration.ofDays(1));
    assertThat(SignedMarkRevocationList.get().getCreationTime())
        .isEqualTo(Instant.parse("2000-01-01T00:00:00Z"));
  }

  @Test
  void test_isSmdRevoked_present() {
    final int rows = 100;
    SignedMarkRevocationList smdrl = createSaveGetHelper(rows);
    assertThat(smdrl.isSmdRevoked("0", clock.now())).isTrue();
    assertThat(smdrl.isSmdRevoked(Integer.toString(rows - 1), clock.now())).isTrue();
    assertThat(smdrl.isSmdRevoked(Integer.toString(rows), clock.now())).isFalse();
  }

  @Test
  void test_isSmdRevoked_future() {
    final int rows = 100;
    SignedMarkRevocationList smdrl = createSaveGetHelper(rows);
    clock.advanceOneMilli();
    assertThat(smdrl.isSmdRevoked("0", clock.now())).isTrue();
    assertThat(smdrl.isSmdRevoked(Integer.toString(rows - 1), clock.now())).isTrue();
    assertThat(smdrl.isSmdRevoked(Integer.toString(rows), clock.now())).isFalse();
  }

  @Test
  void test_isSmdRevoked_past() {
    final int rows = 100;
    SignedMarkRevocationList smdrl = createSaveGetHelper(rows);
    clock.setTo(clock.now().minusMillis(1));
    assertThat(smdrl.isSmdRevoked("0", clock.now())).isFalse();
    assertThat(smdrl.isSmdRevoked(Integer.toString(rows - 1), clock.now())).isFalse();
    assertThat(smdrl.isSmdRevoked(Integer.toString(rows), clock.now())).isFalse();
  }
}
