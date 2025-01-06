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

package google.registry.security;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.util.DateTimeUtils.START_OF_TIME;

import com.google.common.base.Splitter;
import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.persistence.transaction.JpaTestExtensions.JpaIntegrationTestExtension;
import google.registry.testing.FakeClock;
import org.joda.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Tests for {@link XsrfTokenManager}. */
class XsrfTokenManagerTest {

  @RegisterExtension
  final JpaIntegrationTestExtension jpa =
      new JpaTestExtensions.Builder().buildIntegrationTestExtension();

  private final String email = "test@example.com";
  private final FakeClock clock = new FakeClock(START_OF_TIME);
  private final XsrfTokenManager xsrfTokenManager = new XsrfTokenManager(clock);

  private String token;

  @BeforeEach
  void beforeEach() {
    token = xsrfTokenManager.generateToken(email);
  }

  @Test
  void testValidate_validToken() {
    assertThat(xsrfTokenManager.validateToken(email, token)).isTrue();
  }

  @Test
  void testValidate_tokenWithMissingParts() {
    assertThat(xsrfTokenManager.validateToken(email, "1:123")).isFalse();
  }

  @Test
  void testValidate_tokenWithBadVersion() {
    assertThat(xsrfTokenManager.validateToken(email, "2:123:base64")).isFalse();
  }

  @Test
  void testValidate_tokenWithBadNumberTimestamp() {
    assertThat(xsrfTokenManager.validateToken(email, "1:notanumber:base64")).isFalse();
  }

  @Test
  void testValidate_tokenExpiresAfterOneDay() {
    clock.advanceBy(Duration.standardDays(1));
    assertThat(xsrfTokenManager.validateToken(email, token)).isTrue();
    clock.advanceOneMilli();
    assertThat(xsrfTokenManager.validateToken(email, token)).isFalse();
  }

  @Test
  void testValidate_tokenTimestampTamperedWith() {
    String encodedPart = Splitter.on(':').splitToList(token).get(2);
    long fakeTimestamp = clock.nowUtc().plusMillis(1).getMillis();
    assertThat(xsrfTokenManager.validateToken(email, "1:" + fakeTimestamp + ':' + encodedPart))
        .isFalse();
  }

  @Test
  void testValidate_tokenForDifferentUser() {
    String otherToken = xsrfTokenManager.generateToken("eve@example.com");
    assertThat(xsrfTokenManager.validateToken(email, otherToken)).isFalse();
  }
}
