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

import static google.registry.testing.EppExceptionSubject.assertAboutEppExceptions;
import static org.joda.time.format.ISODateTimeFormat.dateTimeNoMillis;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableMap;
import google.registry.flows.EppException;
import google.registry.flows.FlowTestCase;
import google.registry.flows.FlowUtils.GenericXmlSyntaxErrorException;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link HelloFlow}. */
class HelloFlowTest extends FlowTestCase<HelloFlow> {

  @Test
  void testHello() throws Exception {
    setEppInput("hello.xml");
    assertTransactionalFlow(false);
    runFlowAssertResponse(
        loadFile(
            "greeting.xml", ImmutableMap.of("DATE", clock.nowUtc().toString(dateTimeNoMillis()))));
  }

  @Test
  void testGenericSyntaxException() {
    // This is a generic syntax test--we don't have a generic flow test case so this simple
    // test class will do. Note: the logic this tests is common to all flows.
    setEppInput("generic_syntax_exception.xml");
    EppException thrown = assertThrows(GenericXmlSyntaxErrorException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }
}
