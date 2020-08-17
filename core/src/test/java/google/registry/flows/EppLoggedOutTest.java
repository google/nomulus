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

import static org.joda.time.DateTimeZone.UTC;
import static org.joda.time.format.ISODateTimeFormat.dateTimeNoMillis;

import com.google.common.collect.ImmutableMap;
import google.registry.testing.AppEngineExtension;
import org.joda.time.DateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Test flows without login. */
class EppLoggedOutTest extends EppTestCase {

  @RegisterExtension
  final AppEngineExtension appEngine =
      AppEngineExtension.builder().withDatastoreAndCloudSql().build();

  @Test
  void testHello() throws Exception {
    DateTime now = DateTime.now(UTC);
    assertThatCommand("hello.xml", null)
        .atTime(now)
        .hasResponse("greeting.xml", ImmutableMap.of("DATE", now.toString(dateTimeNoMillis())));
  }

  @Test
  void testSyntaxError() throws Exception {
    assertThatCommand("syntax_error.xml")
        .hasResponse(
            "response_error_no_cltrid.xml",
            ImmutableMap.of(
                "CODE", "2001",
                "MSG", "Syntax error at line 4, column 65: cvc-complex-type.3.2.2: "
                    + "Attribute 'xsi:schemaLocation' is not allowed to appear in element 'epp'."));
  }
}
