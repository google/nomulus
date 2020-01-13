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

import static google.registry.testing.DatastoreHelper.deleteResource;
import static google.registry.testing.DatastoreHelper.loadRegistrar;
import static google.registry.testing.DatastoreHelper.persistResource;
import static google.registry.testing.EppExceptionSubject.assertAboutEppExceptions;
import static org.junit.Assert.assertThrows;

import google.registry.flows.EppException;
import google.registry.flows.EppException.UnimplementedExtensionException;
import google.registry.flows.EppException.UnimplementedObjectServiceException;
import google.registry.flows.EppException.UnimplementedProtocolVersionException;
import google.registry.flows.FlowTestCase;
import google.registry.flows.TransportCredentials.BadRegistrarPasswordException;
import google.registry.flows.session.LoginFlow.AlreadyLoggedInException;
import google.registry.flows.session.LoginFlow.BadRegistrarClientIdException;
import google.registry.flows.session.LoginFlow.PasswordChangesNotSupportedException;
import google.registry.flows.session.LoginFlow.RegistrarAccountNotActiveException;
import google.registry.flows.session.LoginFlow.TooManyFailedLoginsException;
import google.registry.flows.session.LoginFlow.UnsupportedLanguageException;
import google.registry.model.registrar.Registrar;
import google.registry.model.registrar.Registrar.State;
import org.junit.Before;
import org.junit.Test;

/** Unit tests for {@link LoginFlow}. */
public abstract class LoginFlowTestCase extends FlowTestCase<LoginFlow> {

  Registrar registrar;
  Registrar.Builder registrarBuilder;

  @Before
  public void initRegistrar() {
    sessionMetadata.setClientId(null); // Don't implicitly log in (all other flows need to).
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
    assertTransactionalFlow(false);
    runFlowAssertResponse(loadFile("generic_success_response.xml"));
  }

  // Also called in subclasses.
  void doFailingTest(String xmlFilename, Class<? extends EppException> exception) {
    setEppInput(xmlFilename);
    EppException thrown = assertThrows(exception, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  public void testSuccess() throws Exception {
    doSuccessfulTest("login_valid.xml");
  }

  @Test
  public void testSuccess_suspendedRegistrar() throws Exception {
    persistResource(getRegistrarBuilder().setState(State.SUSPENDED).build());
    doSuccessfulTest("login_valid.xml");
  }

  @Test
  public void testSuccess_missingTypes() throws Exception {
    // We don't actually care if you list all the right types, as long as you don't add wrong ones.
    doSuccessfulTest("login_valid_missing_types.xml");
  }

  @Test
  public void testFailure_invalidVersion() {
    doFailingTest("login_invalid_version.xml", UnimplementedProtocolVersionException.class);
  }

  @Test
  public void testFailure_invalidLanguage() {
    doFailingTest("login_invalid_language.xml", UnsupportedLanguageException.class);
  }

  @Test
  public void testFailure_invalidExtension() {
    doFailingTest("login_invalid_extension.xml", UnimplementedExtensionException.class);
  }

  @Test
  public void testFailure_invalidTypes() {
    doFailingTest("login_invalid_types.xml", UnimplementedObjectServiceException.class);
  }

  @Test
  public void testFailure_newPassword() {
    doFailingTest("login_invalid_newpw.xml", PasswordChangesNotSupportedException.class);
  }

  @Test
  public void testFailure_unknownRegistrar() {
    deleteResource(getRegistrarBuilder().build());
    doFailingTest("login_valid.xml", BadRegistrarClientIdException.class);
  }

  @Test
  public void testFailure_pendingRegistrar() {
    persistResource(getRegistrarBuilder().setState(State.PENDING).build());
    doFailingTest("login_valid.xml", RegistrarAccountNotActiveException.class);
  }

  @Test
  public void testFailure_disabledRegistrar() {
    persistResource(getRegistrarBuilder().setState(State.DISABLED).build());
    doFailingTest("login_valid.xml", RegistrarAccountNotActiveException.class);
  }

  @Test
  public void testFailure_incorrectPassword() {
    persistResource(getRegistrarBuilder().setPassword("diff password").build());
    doFailingTest("login_valid.xml", BadRegistrarPasswordException.class);
  }

  @Test
  public void testFailure_tooManyFailedLogins() {
    persistResource(getRegistrarBuilder().setPassword("diff password").build());
    doFailingTest("login_valid.xml", BadRegistrarPasswordException.class);
    doFailingTest("login_valid.xml", BadRegistrarPasswordException.class);
    doFailingTest("login_valid.xml", BadRegistrarPasswordException.class);
    doFailingTest("login_valid.xml", TooManyFailedLoginsException.class);
  }

  @Test
  public void testFailure_alreadyLoggedIn() {
    sessionMetadata.setClientId("something");
    doFailingTest("login_valid.xml", AlreadyLoggedInException.class);
  }
}
