// Copyright 2019 The Nomulus Authors. All Rights Reserved.
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

package google.registry.monitoring.blackbox.token;

import static com.google.common.truth.Truth.assertThat;

import com.google.common.collect.ImmutableList;
import google.registry.monitoring.blackbox.exception.UndeterminedStateException;
import google.registry.monitoring.blackbox.message.HttpRequestMessage;
import org.junit.jupiter.api.Test;

/** Unit Tests for {@link WebWhoisToken} */
class WebWhoisTokenTest {

  private static final String PREFIX = "whois.nic.";
  private static final String HOST = "starter";
  private static final String FIRST_TLD = "first_test";
  private static final String SECOND_TLD = "second_test";
  private static final String THIRD_TLD = "third_test";
  private final ImmutableList<String> testDomains =
      ImmutableList.of(FIRST_TLD, SECOND_TLD, THIRD_TLD);

  private Token webToken = new WebWhoisToken(testDomains);

  @Test
  void testMessageModification() throws UndeterminedStateException {
    // creates Request message with header
    HttpRequestMessage message = new HttpRequestMessage();
    message.headers().set("host", HOST);

    // attempts to use Token's method for modifying the method based on its stored host
    HttpRequestMessage secondMessage = (HttpRequestMessage) webToken.modifyMessage(message);
    assertThat(secondMessage.headers().get("host")).isEqualTo(PREFIX + FIRST_TLD);
  }

  @Test
  void testHostOfToken() {
    assertThat(webToken.host()).isEqualTo(PREFIX + FIRST_TLD);
    assertThat(webToken.host()).isEqualTo(PREFIX + FIRST_TLD);
  }

  @Test
  void testNextToken() {
    assertThat(webToken.host()).isEqualTo(PREFIX + FIRST_TLD);
    webToken = webToken.next();

    assertThat(webToken.host()).isEqualTo(PREFIX + SECOND_TLD);
    webToken = webToken.next();

    assertThat(webToken.host()).isEqualTo(PREFIX + THIRD_TLD);
    webToken = webToken.next();

    assertThat(webToken.host()).isEqualTo(PREFIX + FIRST_TLD);
  }
}
