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

package google.registry.export;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.export.SyncGroupMembersAction.getGroupEmailAddressForContactType;
import static google.registry.model.ofy.ObjectifyService.ofy;
import static google.registry.model.registrar.RegistrarContact.Type.ADMIN;
import static google.registry.model.registrar.RegistrarContact.Type.MARKETING;
import static google.registry.model.registrar.RegistrarContact.Type.TECH;
import static google.registry.testing.DatastoreHelper.loadRegistrar;
import static google.registry.testing.DatastoreHelper.persistResource;
import static javax.servlet.http.HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
import static javax.servlet.http.HttpServletResponse.SC_OK;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import google.registry.groups.DirectoryGroupsConnection;
import google.registry.groups.GroupsConnection.Role;
import google.registry.model.registrar.Registrar;
import google.registry.model.registrar.RegistrarContact;
import google.registry.request.Response;
import google.registry.testing.AppEngineRule;
import google.registry.testing.FakeClock;
import google.registry.testing.FakeSleeper;
import google.registry.testing.InjectRule;
import google.registry.util.Retrier;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Unit tests for {@link SyncGroupMembersAction}.
 *
 * <p>Note that this relies on the registrars NewRegistrar and TheRegistrar created by default in
 * {@link AppEngineRule}.
 */
public class SyncGroupMembersActionTest {

  @RegisterExtension
  public final AppEngineRule appEngine = AppEngineRule.builder().withDatastoreAndCloudSql().build();

  @RegisterExtension public final InjectRule inject = new InjectRule();

  private final DirectoryGroupsConnection connection = mock(DirectoryGroupsConnection.class);
  private final Response response = mock(Response.class);

  private void runAction() {
    SyncGroupMembersAction action = new SyncGroupMembersAction();
    action.groupsConnection = connection;
    action.gSuiteDomainName = "domain-registry.example";
    action.response = response;
    action.retrier = new Retrier(new FakeSleeper(new FakeClock()), 3);
    action.run();
  }

  @Test
  void test_getGroupEmailAddressForContactType_convertsToLowercase() {
    assertThat(getGroupEmailAddressForContactType(
            "SomeRegistrar",
            RegistrarContact.Type.ADMIN,
            "domain-registry.example"))
        .isEqualTo("someregistrar-primary-contacts@domain-registry.example");
  }

  @Test
  void test_getGroupEmailAddressForContactType_convertsNonAlphanumericChars() {
    assertThat(getGroupEmailAddressForContactType(
            "Weird.ಠ_ಠRegistrar",
              MARKETING,
            "domain-registry.example"))
        .isEqualTo("weirdregistrar-marketing-contacts@domain-registry.example");
  }

  @Test
  void test_doPost_noneModified() {
    persistResource(
        loadRegistrar("NewRegistrar").asBuilder().setContactsRequireSyncing(false).build());
    persistResource(
        loadRegistrar("TheRegistrar").asBuilder().setContactsRequireSyncing(false).build());
    runAction();
    verify(response).setStatus(SC_OK);
    verify(response).setPayload("NOT_MODIFIED No registrar contacts have been updated "
        + "since the last time servlet ran.\n");
    assertThat(loadRegistrar("NewRegistrar").getContactsRequireSyncing()).isFalse();
  }

  @Test
  void test_doPost_syncsNewContact() throws Exception {
    runAction();
    verify(connection).addMemberToGroup(
        "newregistrar-primary-contacts@domain-registry.example",
        "janedoe@theregistrar.com",
        Role.MEMBER);
    verify(response).setStatus(SC_OK);
    verify(response).setPayload("OK Group memberships successfully updated.\n");
    assertThat(loadRegistrar("NewRegistrar").getContactsRequireSyncing()).isFalse();
  }

  @Test
  void test_doPost_removesOldContact() throws Exception {
    when(connection.getMembersOfGroup("newregistrar-primary-contacts@domain-registry.example"))
        .thenReturn(ImmutableSet.of("defunct@example.com", "janedoe@theregistrar.com"));
    runAction();
    verify(connection).removeMemberFromGroup(
        "newregistrar-primary-contacts@domain-registry.example", "defunct@example.com");
    verify(response).setStatus(SC_OK);
    assertThat(loadRegistrar("NewRegistrar").getContactsRequireSyncing()).isFalse();
  }

  @Test
  void test_doPost_removesAllContactsFromGroup() throws Exception {
    when(connection.getMembersOfGroup("newregistrar-primary-contacts@domain-registry.example"))
        .thenReturn(ImmutableSet.of("defunct@example.com", "janedoe@theregistrar.com"));
    ofy().deleteWithoutBackup()
        .entities(loadRegistrar("NewRegistrar").getContacts())
        .now();
    runAction();
    verify(connection).removeMemberFromGroup(
        "newregistrar-primary-contacts@domain-registry.example", "defunct@example.com");
    verify(connection).removeMemberFromGroup(
        "newregistrar-primary-contacts@domain-registry.example", "janedoe@theregistrar.com");
    verify(response).setStatus(SC_OK);
    assertThat(loadRegistrar("NewRegistrar").getContactsRequireSyncing()).isFalse();
  }

  @Test
  void test_doPost_addsAndRemovesContacts_acrossMultipleRegistrars() throws Exception {
    when(connection.getMembersOfGroup("newregistrar-primary-contacts@domain-registry.example"))
        .thenReturn(ImmutableSet.of("defunct@example.com", "janedoe@theregistrar.com"));
    when(connection.getMembersOfGroup("newregistrar-marketing-contacts@domain-registry.example"))
        .thenReturn(ImmutableSet.of());
    when(connection.getMembersOfGroup("theregistrar-technical-contacts@domain-registry.example"))
        .thenReturn(ImmutableSet.of());
    when(connection.getMembersOfGroup("theregistrar-primary-contacts@domain-registry.example"))
        .thenReturn(ImmutableSet.of());
    persistResource(
        new RegistrarContact.Builder()
            .setParent(loadRegistrar("NewRegistrar"))
            .setName("Binary Star")
            .setEmailAddress("binarystar@example.tld")
            .setTypes(ImmutableSet.of(ADMIN, MARKETING))
            .build());
    persistResource(
        new RegistrarContact.Builder()
            .setParent(loadRegistrar("TheRegistrar"))
            .setName("Hexadecimal")
            .setEmailAddress("hexadecimal@snow.fall")
            .setTypes(ImmutableSet.of(TECH))
            .build());
    runAction();
    verify(connection).removeMemberFromGroup(
        "newregistrar-primary-contacts@domain-registry.example", "defunct@example.com");
    verify(connection).addMemberToGroup(
        "newregistrar-primary-contacts@domain-registry.example",
        "binarystar@example.tld",
        Role.MEMBER);
    verify(connection).addMemberToGroup(
        "newregistrar-marketing-contacts@domain-registry.example",
        "binarystar@example.tld",
        Role.MEMBER);
    verify(connection).addMemberToGroup(
        "theregistrar-primary-contacts@domain-registry.example",
        "johndoe@theregistrar.com",
        Role.MEMBER);
    verify(connection).addMemberToGroup(
        "theregistrar-technical-contacts@domain-registry.example",
        "hexadecimal@snow.fall",
        Role.MEMBER);
    verify(response).setStatus(SC_OK);
    assertThat(Iterables.filter(Registrar.loadAll(), Registrar::getContactsRequireSyncing))
        .isEmpty();
  }

  @Test
  void test_doPost_gracefullyHandlesExceptionForSingleRegistrar() throws Exception {
    when(connection.getMembersOfGroup("newregistrar-primary-contacts@domain-registry.example"))
        .thenReturn(ImmutableSet.of());
    when(connection.getMembersOfGroup("theregistrar-primary-contacts@domain-registry.example"))
        .thenThrow(new IOException("Internet was deleted"));
    runAction();
    verify(connection).addMemberToGroup(
        "newregistrar-primary-contacts@domain-registry.example",
        "janedoe@theregistrar.com",
        Role.MEMBER);
    verify(connection, times(3))
        .getMembersOfGroup("theregistrar-primary-contacts@domain-registry.example");
    verify(response).setStatus(SC_INTERNAL_SERVER_ERROR);
    verify(response).setPayload("FAILED Error occurred while updating registrar contacts.\n");
    assertThat(loadRegistrar("NewRegistrar").getContactsRequireSyncing()).isFalse();
    assertThat(loadRegistrar("TheRegistrar").getContactsRequireSyncing()).isTrue();
  }

  @Test
  void test_doPost_retriesOnTransientException() throws Exception {
    doThrow(IOException.class)
        .doNothing()
        .when(connection)
        .addMemberToGroup(anyString(), anyString(), any(Role.class));
    runAction();
    verify(connection, times(2)).addMemberToGroup(
        "newregistrar-primary-contacts@domain-registry.example",
        "janedoe@theregistrar.com",
        Role.MEMBER);
    verify(response).setStatus(SC_OK);
    verify(response).setPayload("OK Group memberships successfully updated.\n");
    assertThat(loadRegistrar("NewRegistrar").getContactsRequireSyncing()).isFalse();
  }
}
