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

import static com.google.common.truth.Truth.assertThat;
import static google.registry.testing.DatabaseHelper.deleteResource;
import static google.registry.testing.DatabaseHelper.loadRegistrar;
import static google.registry.testing.DatabaseHelper.persistResource;
import static google.registry.testing.EppExceptionSubject.assertAboutEppExceptions;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.common.collect.ImmutableMap;
import google.registry.flows.EppException;
import google.registry.flows.EppException.UnimplementedExtensionException;
import google.registry.flows.EppException.UnimplementedObjectServiceException;
import google.registry.flows.EppException.UnimplementedProtocolVersionException;
import google.registry.flows.FlowTestCase;
import google.registry.flows.FlowUtils.GenericXmlSyntaxErrorException;
import google.registry.flows.TransportCredentials.BadRegistrarPasswordException;
import google.registry.flows.session.LoginFlow.AlreadyLoggedInException;
import google.registry.flows.session.LoginFlow.BadRegistrarIdException;
import google.registry.flows.session.LoginFlow.RegistrarAccountNotActiveException;
import google.registry.flows.session.LoginFlow.TooManyFailedLoginsException;
import google.registry.flows.session.LoginFlow.UnsupportedLanguageException;
import google.registry.model.eppoutput.EppOutput;
import google.registry.model.registrar.Registrar;
import google.registry.model.registrar.Registrar.State;
import google.registry.testing.DatabaseHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link LoginFlow}. */
public abstract class LoginFlowTestCase extends FlowTestCase<LoginFlow> {

  private Registrar registrar;
  private Registrar.Builder registrarBuilder;

  @BeforeEach
  void beforeEachLoginFlowTestCase() {
    sessionMetadata.setRegistrarId(null); // Don't implicitly log in (all other flows need to).
    registrar = loadRegistrar("NewRegistrar");
    registrarBuilder = registrar.asBuilder();
  }

  // Can't inline this since it may be overridden in subclasses.
  protected Registrar.Builder getRegistrarBuilder() {
    return registrarBuilder;
  }

  // Also called in subclasses.
  void doSuccessfulTest(String xmlFilename) throws Exception {
    setEppInput(xmlFilename);
    assertMutatingFlow(true);
    runFlowAssertResponse(loadFile("generic_success_response.xml"));
  }

  // Also called in subclasses.
  void doFailingTest(String xmlFilename, Class<? extends EppException> exception) {
    setEppInput(xmlFilename);
    EppException thrown = assertThrows(exception, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testSuccess() throws Exception {
    doSuccessfulTest("login_valid.xml");
  }

  @Test
  void testSuccess_setsIsLoginResponse() throws Exception {
    setEppInput("login_valid.xml");
    assertMutatingFlow(true);
    EppOutput output = runFlow();
    assertThat(output.getResponse().isLoginResponse()).isTrue();
  }

  @Test
  void testSuccess_suspendedRegistrar() throws Exception {
    persistResource(getRegistrarBuilder().setState(State.SUSPENDED).build());
    doSuccessfulTest("login_valid.xml");
  }

  @Test
  void testSuccess_missingTypes() throws Exception {
    // We don't actually care if you list all the right types, as long as you don't add wrong ones.
    doSuccessfulTest("login_valid_missing_types.xml");
  }

  @Test
  void testFailure_invalidVersion() {
    doFailingTest("login_invalid_version.xml", UnimplementedProtocolVersionException.class);
  }

  @Test
  void testFailure_invalidLanguage() {
    doFailingTest("login_invalid_language.xml", UnsupportedLanguageException.class);
  }

  @Test
  void testFailure_invalidExtension() {
    doFailingTest("login_invalid_extension.xml", UnimplementedExtensionException.class);
  }

  @Test
  void testFailure_invalidTypes() {
    doFailingTest("login_invalid_types.xml", UnimplementedObjectServiceException.class);
  }

  @Test
  void testSetNewPassword() throws Exception {
    assertThat(registrar.verifyPassword("foo-BAR2")).isTrue();
    assertThat(registrar.verifyPassword("ANewPassword")).isFalse();
    assertThat(registrar.verifyPassword("randomstring")).isFalse();

    setEppInput("login_set_new_password.xml", ImmutableMap.of("NEWPW", "ANewPassword"));
    assertMutatingFlow(true);
    runFlowAssertResponse(loadFile("generic_success_response.xml"));

    Registrar newRegistrar = loadRegistrar("NewRegistrar");
    assertThat(newRegistrar.verifyPassword("foo-BAR2")).isFalse();
    assertThat(newRegistrar.verifyPassword("ANewPassword")).isTrue();
    assertThat(registrar.verifyPassword("randomstring")).isFalse();
  }

  @Test
  void testFailure_invalidNewPassword_tooShort() throws Exception {
    setEppInput("login_set_new_password.xml", ImmutableMap.of("NEWPW", "5Char"));
    EppException thrown = assertThrows(GenericXmlSyntaxErrorException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
    assertThat(thrown)
        .hasMessageThat()
        .contains(
            "length = '5' is not facet-valid with respect to minLength '6' for type 'pwType'");
  }

  @Test
  void testFailure_invalidNewPassword_tooLong() throws Exception {
    setEppInput(
        "login_set_new_password.xml", ImmutableMap.of("NEWPW", "ThisIsMoreThan16Characters"));
    EppException thrown = assertThrows(GenericXmlSyntaxErrorException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
    assertThat(thrown)
        .hasMessageThat()
        .contains(
            "length = '26' is not facet-valid with respect to maxLength '16' for type 'pwType'");
  }

  @Test
  void testFailure_invalidNewPassword_containsInvalidCharacter() throws Exception {
    setEppInput("login_set_new_password.xml", ImmutableMap.of("NEWPW", "TheChar&IsNotValid"));
    EppException thrown = assertThrows(GenericXmlSyntaxErrorException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
    // Just generically assert on this error message because it's a pretty broad error owing to the
    // overall XML simply not parsing correctly.
    assertThat(thrown).hasMessageThat().contains("Syntax error");
  }

  @Test
  void testFailure_unknownRegistrar() {
    registrar.getContacts().forEach(DatabaseHelper::deleteResource);
    deleteResource(registrar);
    doFailingTest("login_valid.xml", BadRegistrarIdException.class);
  }

  @Test
  void testFailure_pendingRegistrar() {
    persistResource(getRegistrarBuilder().setState(State.PENDING).build());
    doFailingTest("login_valid.xml", RegistrarAccountNotActiveException.class);
  }

  @Test
  void testFailure_disabledRegistrar() {
    persistResource(getRegistrarBuilder().setState(State.DISABLED).build());
    doFailingTest("login_valid.xml", RegistrarAccountNotActiveException.class);
  }

  @Test
  void testFailure_incorrectPassword() {
    persistResource(getRegistrarBuilder().setPassword("diff password").build());
    doFailingTest("login_valid.xml", BadRegistrarPasswordException.class);
  }

  @Test
  void testFailure_tooManyFailedLogins() {
    persistResource(getRegistrarBuilder().setPassword("diff password").build());
    doFailingTest("login_valid.xml", BadRegistrarPasswordException.class);
    doFailingTest("login_valid.xml", BadRegistrarPasswordException.class);
    doFailingTest("login_valid.xml", BadRegistrarPasswordException.class);
    doFailingTest("login_valid.xml", TooManyFailedLoginsException.class);
  }

  @Test
  void testFailure_alreadyLoggedIn() {
    sessionMetadata.setRegistrarId("something");
    doFailingTest("login_valid.xml", AlreadyLoggedInException.class);
  }
}
