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

package google.registry.security;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.util.DateTimeUtils.START_OF_TIME;

import com.google.appengine.api.users.User;
import com.google.common.base.Splitter;
import google.registry.testing.AppEngineExtension;
import google.registry.testing.FakeClock;
import google.registry.testing.FakeUserService;
import google.registry.testing.InjectRule;
import org.joda.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Tests for {@link XsrfTokenManager}. */
class XsrfTokenManagerTest {

  @RegisterExtension
  final AppEngineExtension appEngine =
      AppEngineExtension.builder().withDatastoreAndCloudSql().build();

  @RegisterExtension InjectRule inject = new InjectRule();

  private final User testUser = new User("test@example.com", "test@example.com");
  private final FakeClock clock = new FakeClock(START_OF_TIME);
  private final FakeUserService userService = new FakeUserService();
  private final XsrfTokenManager xsrfTokenManager = new XsrfTokenManager(clock, userService);

  private String token;

  @BeforeEach
  void beforeEach() {
    userService.setUser(testUser, false);
    token = xsrfTokenManager.generateToken(testUser.getEmail());
  }

  @Test
  void testValidate_validToken() {
    assertThat(xsrfTokenManager.validateToken(token)).isTrue();
  }

  @Test
  void testValidate_tokenWithMissingParts() {
    assertThat(xsrfTokenManager.validateToken("1:123")).isFalse();
  }

  @Test
  void testValidate_tokenWithBadVersion() {
    assertThat(xsrfTokenManager.validateToken("2:123:base64")).isFalse();
  }

  @Test
  void testValidate_tokenWithBadNumberTimestamp() {
    assertThat(xsrfTokenManager.validateToken("1:notanumber:base64")).isFalse();
  }

  @Test
  void testValidate_tokenExpiresAfterOneDay() {
    clock.advanceBy(Duration.standardDays(1));
    assertThat(xsrfTokenManager.validateToken(token)).isTrue();
    clock.advanceOneMilli();
    assertThat(xsrfTokenManager.validateToken(token)).isFalse();
  }

  @Test
  void testValidate_tokenTimestampTamperedWith() {
    String encodedPart = Splitter.on(':').splitToList(token).get(2);
    long fakeTimestamp = clock.nowUtc().plusMillis(1).getMillis();
    assertThat(xsrfTokenManager.validateToken("1:" + fakeTimestamp + ":" + encodedPart)).isFalse();
  }

  @Test
  void testValidate_tokenForDifferentUser() {
    String otherToken = xsrfTokenManager.generateToken("eve@example.com");
    assertThat(xsrfTokenManager.validateToken(otherToken)).isFalse();
  }
}
