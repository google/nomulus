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
import google.registry.monitoring.blackbox.messages.EppRequestMessage;
import java.io.IOException;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Unit Tests for each {@link Token} subtype (just {@link WebWhoisToken} for now)
 */
@RunWith(JUnit4.class)
public class EppTokenTest {

  private static String TEST_HOST = "host";
  private static String TEST_TLD = "tld";

  private Token eppToken = new EppToken.Persistent(TEST_TLD, TEST_HOST);

  @Test
  public void testEppToken_MessageModificationSuccess()
      throws UndeterminedStateException {
    EppRequestMessage originalMessage = new EppRequestMessage.CREATE();
    String domainName = ((EppToken)eppToken).getCurrentDomainName();
    String clTRID = domainName.substring(0, domainName.indexOf('.'));

    EppRequestMessage modifiedMessage = (EppRequestMessage) eppToken.modifyMessage(originalMessage);

    assertThat(modifiedMessage.getElementValue("//domainns:name")).isEqualTo(domainName);
    assertThat(modifiedMessage.getClTRID()).isNotEqualTo(clTRID);

  }

}
