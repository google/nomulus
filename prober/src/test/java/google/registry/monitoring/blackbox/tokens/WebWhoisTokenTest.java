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

package google.registry.monitoring.blackbox.tokens;

import static com.google.common.truth.Truth.assertThat;

import com.google.common.collect.ImmutableList;
import google.registry.monitoring.blackbox.exceptions.UndeterminedStateException;
import google.registry.monitoring.blackbox.messages.HttpRequestMessage;
import google.registry.monitoring.blackbox.tokens.Token;
import google.registry.monitoring.blackbox.tokens.WebWhoisToken;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Unit Tests for {@link WebWhoisToken}
 */
@RunWith(JUnit4.class)
public class WebWhoisTokenTest {

  private static String PREFIX = "whois.nic.";
  private static String HOST = "starter";
  private static String FIRST_TLD = "first_test";
  private static String SECOND_TLD = "second_test";
  private static String THIRD_TLD = "third_test";
  private static ImmutableList<String> TEST_DOMAINS = ImmutableList.of(FIRST_TLD, SECOND_TLD,
      THIRD_TLD);

  public Token webToken = new WebWhoisToken(TEST_DOMAINS);

  @Test
  public void testMessageModification() throws UndeterminedStateException {
    //creates Request message with header
    HttpRequestMessage message = new HttpRequestMessage();
    message.headers().set("host", HOST);

    //attempts to use Token's method for modifying the method based on its stored host
    HttpRequestMessage secondMessage = (HttpRequestMessage) webToken.modifyMessage(message);
    assertThat(secondMessage.headers().get("host")).isEqualTo(PREFIX + TEST_DOMAINS.get(0));
  }

  /**
   * As Circular Linked List has not been implemented yet, we cannot yet wrap around, so we don't
   * test that in testing {@code next}.
   */
  @Test
  public void testNextToken() {
    assertThat(webToken.host()).isEqualTo(PREFIX + FIRST_TLD);
    webToken = webToken.next();

    assertThat(webToken.host()).isEqualTo(PREFIX + SECOND_TLD);
    webToken = webToken.next();

    assertThat(webToken.host()).isEqualTo(PREFIX + THIRD_TLD);
  }

}
