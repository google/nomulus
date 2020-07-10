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

package google.registry.model.eppinput;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth8.assertThat;
import static google.registry.model.eppcommon.EppXmlTransformer.unmarshal;
import static google.registry.testing.TestDataHelper.loadBytes;
import static org.junit.Assert.assertThrows;

import google.registry.model.contact.ContactResourceTest;
import google.registry.model.domain.DomainBaseTest;
import google.registry.model.eppinput.EppInput.InnerCommand;
import google.registry.model.eppinput.EppInput.Login;
import google.registry.xml.XmlException;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link EppInput}. */
class EppInputTest {

  @Test
  void testUnmarshalling_contactInfo() throws Exception {
    EppInput input =
        unmarshal(EppInput.class, loadBytes(ContactResourceTest.class, "contact_info.xml").read());
    assertThat(input.getCommandWrapper().getClTrid()).hasValue("ABC-12345");
    assertThat(input.getCommandType()).isEqualTo("info");
    assertThat(input.getResourceType()).hasValue("contact");
    assertThat(input.getSingleTargetId()).hasValue("sh8013");
    assertThat(input.getTargetIds()).containsExactly("sh8013");
  }

  @Test
  void testUnmarshalling_domainCheck() throws Exception {
    EppInput input =
        unmarshal(EppInput.class, loadBytes(DomainBaseTest.class, "domain_check.xml").read());
    assertThat(input.getCommandWrapper().getClTrid()).hasValue("ABC-12345");
    assertThat(input.getCommandType()).isEqualTo("check");
    assertThat(input.getResourceType()).hasValue("domain");
    assertThat(input.getSingleTargetId()).isEmpty();
    assertThat(input.getTargetIds()).containsExactly("example.com", "example.net", "example.org");
  }

  @Test
  void testUnmarshalling_login() throws Exception {
    EppInput input = unmarshal(EppInput.class, loadBytes(getClass(), "login_valid.xml").read());
    assertThat(input.getCommandWrapper().getClTrid()).hasValue("ABC-12345");
    assertThat(input.getCommandType()).isEqualTo("login");
    assertThat(input.getResourceType()).isEmpty();
    assertThat(input.getSingleTargetId()).isEmpty();
    assertThat(input.getTargetIds()).isEmpty();
    InnerCommand command = input.getCommandWrapper().getCommand();
    assertThat(command).isInstanceOf(Login.class);
    Login loginCommand = (Login) command;
    assertThat(loginCommand.clientId).isEqualTo("NewRegistrar");
    assertThat(loginCommand.password).isEqualTo("foo-BAR2");
    assertThat(loginCommand.newPassword).isNull();
    assertThat(loginCommand.options.version).isEqualTo("1.0");
    assertThat(loginCommand.options.language).isEqualTo("en");
    assertThat(loginCommand.services.objectServices)
        .containsExactly(
            "urn:ietf:params:xml:ns:host-1.0",
            "urn:ietf:params:xml:ns:domain-1.0",
            "urn:ietf:params:xml:ns:contact-1.0");
    assertThat(loginCommand.services.serviceExtensions)
        .containsExactly("urn:ietf:params:xml:ns:launch-1.0", "urn:ietf:params:xml:ns:rgp-1.0");
  }

  @Test
  void testUnmarshalling_loginTagInWrongCase_throws() {
    assertThrows(
        XmlException.class,
        () -> unmarshal(EppInput.class, loadBytes(getClass(), "login_wrong_case.xml").read()));
  }
}
