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

import com.google.common.collect.ImmutableMap;
import google.registry.testing.AppEngineRule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Tests for <a href="https://en.wikipedia.org/wiki/XML_external_entity_attack">XXE</a> attacks. */
class EppXxeAttackTest extends EppTestCase {

  @RegisterExtension
  final AppEngineRule appEngine = AppEngineRule.builder().withDatastoreAndCloudSql().build();

  @Test
  void testRemoteXmlExternalEntity() throws Exception {
    assertThatLoginSucceeds("NewRegistrar", "foo-BAR2");
    assertThatCommand("contact_create_remote_xxe.xml")
        .hasResponse(
            "response_error_no_cltrid.xml",
            ImmutableMap.of(
                "CODE", "2001",
                "MSG", "Syntax error at line 11, column 34: "
                    + "The entity &quot;remote&quot; was referenced, but not declared."));
    assertThatLogoutSucceeds();
  }

  @Test
  void testLocalXmlExtrernalEntity() throws Exception {
    assertThatLoginSucceeds("NewRegistrar", "foo-BAR2");
    assertThatCommand("contact_create_local_xxe.xml")
        .hasResponse(
            "response_error_no_cltrid.xml",
            ImmutableMap.of(
                "CODE", "2001",
                "MSG", "Syntax error at line 11, column 31: "
                    + "The entity &quot;ent&quot; was referenced, but not declared."));
    assertThatLogoutSucceeds();
  }

  @Test
  void testBillionLaughsAttack() throws Exception {
    assertThatLoginSucceeds("NewRegistrar", "foo-BAR2");
    assertThatCommand("contact_create_billion_laughs.xml")
        .hasResponse(
            "response_error_no_cltrid.xml",
            ImmutableMap.of(
                "CODE", "2001",
                "MSG", "Syntax error at line 20, column 32: "
                    + "The entity &quot;lol9&quot; was referenced, but not declared."));
    assertThatLogoutSucceeds();
  }
}
