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

import google.registry.monitoring.blackbox.exception.UndeterminedStateException;
import google.registry.monitoring.blackbox.message.EppRequestMessage;
import google.registry.monitoring.blackbox.util.EppUtils;
import io.netty.channel.Channel;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/** Unit Tests for each {@link Token} subtype (just {@link WebWhoisToken} for now) */
class EppTokenTest {

  private static String TEST_HOST = "host";
  private static String TEST_TLD = "tld";

  private EppToken persistentEppToken = new EppToken.Persistent(TEST_TLD, TEST_HOST);
  private EppToken transientEppToken = new EppToken.Transient(TEST_TLD, TEST_HOST);

  @Test
  void testMessageModificationSuccess_PersistentToken() throws UndeterminedStateException {

    EppRequestMessage originalMessage = EppUtils.getCreateMessage(EppUtils.getSuccessResponse());
    String domainName = persistentEppToken.getCurrentDomainName();
    String clTrid = domainName.substring(0, domainName.indexOf('.'));

    EppRequestMessage modifiedMessage =
        (EppRequestMessage) persistentEppToken.modifyMessage(originalMessage);

    // ensure element values are what they should be
    assertThat(modifiedMessage.getElementValue("//domainns:name")).isEqualTo(domainName);
    assertThat(modifiedMessage.getElementValue("//eppns:clTRID")).isNotEqualTo(clTrid);
  }

  @Test
  void testMessageModificationSuccess_TransientToken() throws UndeterminedStateException {

    EppRequestMessage originalMessage = EppUtils.getCreateMessage(EppUtils.getSuccessResponse());
    String domainName = transientEppToken.getCurrentDomainName();
    String clTrid = domainName.substring(0, domainName.indexOf('.'));

    EppRequestMessage modifiedMessage =
        (EppRequestMessage) transientEppToken.modifyMessage(originalMessage);

    // ensure element values are what they should be
    assertThat(modifiedMessage.getElementValue("//domainns:name")).isEqualTo(domainName);
    assertThat(modifiedMessage.getElementValue("//eppns:clTRID")).isNotEqualTo(clTrid);
  }

  @Test
  void testNext_persistentToken() {
    String domainName = persistentEppToken.getCurrentDomainName();
    Channel mockChannel = Mockito.mock(Channel.class);
    persistentEppToken.setChannel(mockChannel);

    EppToken nextToken = (EppToken) persistentEppToken.next();

    assertThat(nextToken.getCurrentDomainName()).isNotEqualTo(domainName);
    assertThat(nextToken.channel()).isEqualTo(mockChannel);
  }

  @Test
  void testNext_transientToken() {
    String domainName = transientEppToken.getCurrentDomainName();
    Channel mockChannel = Mockito.mock(Channel.class);
    transientEppToken.setChannel(mockChannel);

    EppToken nextToken = (EppToken) transientEppToken.next();

    assertThat(nextToken.getCurrentDomainName()).isNotEqualTo(domainName);
    assertThat(nextToken.channel()).isNull();
  }
}
