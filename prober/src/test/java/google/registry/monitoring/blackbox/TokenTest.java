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

package google.registry.monitoring.blackbox;

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
 * Unit Tests for each {@link Token} subtype (just {@link WebWhoisToken} for now)
 */
@RunWith(JUnit4.class)
public class TokenTest {

  private static String PREFIX = "whois.nic.";
  private static String TEST_STARTER = "starter";
  private static ImmutableList<String> TEST_DOMAINS = ImmutableList.of("test");

  public Token webToken = new WebWhoisToken(TEST_DOMAINS);

  @Test
  public void testWebToken_MessageModificationSuccess() {
    //creates Request message with header
    HttpRequestMessage message = new HttpRequestMessage();
    message.headers().set("host", TEST_STARTER);

    //attempts to use Token's method for modifying the method based on its stored host
    try {
      HttpRequestMessage secondMessage = (HttpRequestMessage) webToken.modifyMessage(message);
      assertThat(secondMessage.headers().get("host")).isEqualTo(PREFIX+TEST_DOMAINS.get(0));
    } catch(UndeterminedStateException e) {
      throw new RuntimeException(e);
    }




  }

}
