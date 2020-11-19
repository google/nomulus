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

package google.registry.flows.session;

import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.EppExceptionSubject.assertAboutEppExceptions;
import static org.junit.jupiter.api.Assertions.assertThrows;

import google.registry.flows.EppException;
import google.registry.flows.FlowTestCase;
import google.registry.flows.FlowUtils.NotLoggedInException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link LogoutFlow}. */
class LogoutFlowTest extends FlowTestCase<LogoutFlow> {

  LogoutFlowTest() {
    setEppInput("logout.xml");
  }

  @BeforeEach
  void setupTld() {
    createTld("example");
  }

  @Test
  void testSuccess() throws Exception {
    assertTransactionalFlow(false);
    // All flow tests are implicitly logged in, so logout should work.
    runFlowAssertResponse(loadFile("logout_response.xml"));
  }

  @Test
  void testFailure() {
    sessionMetadata.setClientId(null);  // Turn off the implicit login
    EppException thrown = assertThrows(NotLoggedInException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }
}
