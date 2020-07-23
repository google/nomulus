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

package google.registry.flows;

import static com.google.common.truth.Truth.assertThat;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Tests for {@link EppToolAction}. */
class EppToolActionTest {

  private void doTest(boolean isDryRun, boolean isSuperuser) {
    EppToolAction action = new EppToolAction();
    action.clientId = "ClientIdentifier";
    action.isDryRun = isDryRun;
    action.isSuperuser = isSuperuser;
    action.eppRequestHandler = mock(EppRequestHandler.class);
    action.xml = "<xml>";
    action.run();
    ArgumentCaptor<SessionMetadata> captor = ArgumentCaptor.forClass(SessionMetadata.class);
    verify(action.eppRequestHandler).executeEpp(
        captor.capture(),
        isA(PasswordOnlyTransportCredentials.class),
        eq(EppRequestSource.TOOL),
        eq(isDryRun),
        eq(isSuperuser),
        eq(action.xml.getBytes(UTF_8)));
    assertThat(captor.getValue().getClientId()).isEqualTo("ClientIdentifier");
  }

  @Test
  void testDryRunAndSuperuser() {
    doTest(true, true);
  }

  @Test
  void testDryRun() {
    doTest(true, false);
  }

  @Test
  void testSuperuser() {
    doTest(false, true);
  }

  @Test
  void testNeitherDryRunNorSuperuser() {
    doTest(false, false);
  }
}
