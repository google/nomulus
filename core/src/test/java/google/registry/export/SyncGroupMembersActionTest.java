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
import static google.registry.groups.DirectoryGroupsConnection.SC_TOO_MANY_REQUESTS;
import static google.registry.model.registrar.RegistrarPoc.Type.ADMIN;
import static google.registry.model.registrar.RegistrarPoc.Type.MARKETING;
import static google.registry.model.registrar.RegistrarPoc.Type.TECH;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.testing.DatabaseHelper.loadRegistrar;
import static google.registry.testing.DatabaseHelper.persistResource;
import static jakarta.servlet.http.HttpServletResponse.SC_BAD_REQUEST;
import static jakarta.servlet.http.HttpServletResponse.SC_FORBIDDEN;
import static jakarta.servlet.http.HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
import static jakarta.servlet.http.HttpServletResponse.SC_NOT_FOUND;
import static jakarta.servlet.http.HttpServletResponse.SC_OK;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.client.http.HttpRequest;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.http.LowLevelHttpRequest;
import com.google.api.client.http.LowLevelHttpResponse;
import com.google.api.client.json.Json;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.client.testing.http.HttpTesting;
import com.google.api.client.testing.http.MockHttpTransport;
import com.google.api.client.testing.http.MockLowLevelHttpRequest;
import com.google.api.client.testing.http.MockLowLevelHttpResponse;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.testing.TestLogHandler;
import google.registry.groups.DirectoryGroupsConnection;
import google.registry.groups.GroupsConnection.Role;
import google.registry.model.registrar.Registrar;
import google.registry.model.registrar.RegistrarPoc;
import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.persistence.transaction.JpaTestExtensions.JpaIntegrationTestExtension;
import google.registry.testing.FakeClock;
import google.registry.testing.FakeResponse;
import google.registry.testing.FakeSleeper;
import google.registry.util.Retrier;
import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Unit tests for {@link SyncGroupMembersAction}.
 *
 * <p>Note that this relies on the registrars NewRegistrar and TheRegistrar created by default in
 * {@link JpaIntegrationTestExtension}.
 */
public class SyncGroupMembersActionTest {

  @RegisterExtension
  final JpaIntegrationTestExtension jpa =
      new JpaTestExtensions.Builder().buildIntegrationTestExtension();

  private final DirectoryGroupsConnection connection = mock(DirectoryGroupsConnection.class);
  private final FakeResponse response = new FakeResponse();
  private final TestLogHandler logHandler = new TestLogHandler();
  private final Logger loggerToIntercept =
      Logger.getLogger(SyncGroupMembersAction.class.getCanonicalName());

  @BeforeEach
  void beforeEach() {
    loggerToIntercept.addHandler(logHandler);
  }

  @AfterEach
  void afterEach() {
    loggerToIntercept.removeHandler(logHandler);
  }

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
    assertThat(
            getGroupEmailAddressForContactType("SomeRegistrar", ADMIN, "domain-registry.example"))
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
    assertThat(response.getStatus()).isEqualTo(SC_OK);
    assertThat(response.getPayload())
        .isEqualTo(
            "NOT_MODIFIED No registrar contacts have been updated "
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
    assertThat(response.getStatus()).isEqualTo(SC_OK);
    assertThat(response.getPayload()).isEqualTo("OK Group memberships successfully updated.\n");
    assertThat(loadRegistrar("NewRegistrar").getContactsRequireSyncing()).isFalse();
  }

  @Test
  void test_doPost_removesOldContact() throws Exception {
    when(connection.getMembersOfGroup("newregistrar-primary-contacts@domain-registry.example"))
        .thenReturn(ImmutableSet.of("defunct@example.com", "janedoe@theregistrar.com"));
    runAction();
    verify(connection).removeMemberFromGroup(
        "newregistrar-primary-contacts@domain-registry.example", "defunct@example.com");
    assertThat(response.getStatus()).isEqualTo(SC_OK);
    assertThat(loadRegistrar("NewRegistrar").getContactsRequireSyncing()).isFalse();
  }

  @Test
  void test_doPost_removesAllContactsFromGroup() throws Exception {
    when(connection.getMembersOfGroup("newregistrar-primary-contacts@domain-registry.example"))
        .thenReturn(ImmutableSet.of("defunct@example.com", "janedoe@theregistrar.com"));
    tm().transact(() -> loadRegistrar("NewRegistrar").getContacts().forEach(tm()::delete));
    runAction();
    verify(connection).removeMemberFromGroup(
        "newregistrar-primary-contacts@domain-registry.example", "defunct@example.com");
    verify(connection).removeMemberFromGroup(
        "newregistrar-primary-contacts@domain-registry.example", "janedoe@theregistrar.com");
    assertThat(response.getStatus()).isEqualTo(SC_OK);
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
        new RegistrarPoc.Builder()
            .setRegistrar(loadRegistrar("NewRegistrar"))
            .setName("Binary Star")
            .setEmailAddress("binarystar@example.tld")
            .setTypes(ImmutableSet.of(ADMIN, MARKETING))
            .build());
    persistResource(
        new RegistrarPoc.Builder()
            .setRegistrar(loadRegistrar("TheRegistrar"))
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
    assertThat(response.getStatus()).isEqualTo(SC_OK);
    assertThat(Iterables.filter(Registrar.loadAll(), Registrar::getContactsRequireSyncing))
        .isEmpty();
  }

  @Test
  void test_doPost_gracefullyHandlesExceptionForSingleRegistrar() throws Exception {
    when(connection.getMembersOfGroup("newregistrar-primary-contacts@domain-registry.example"))
        .thenReturn(ImmutableSet.of());
    when(connection.getMembersOfGroup("theregistrar-primary-contacts@domain-registry.example"))
        .thenThrow(new IOException("Internet was neleted"));
    runAction();
    verify(connection).addMemberToGroup(
        "newregistrar-primary-contacts@domain-registry.example",
        "janedoe@theregistrar.com",
        Role.MEMBER);
    verify(connection, times(3))
        .getMembersOfGroup("theregistrar-primary-contacts@domain-registry.example");
    assertThat(response.getStatus()).isEqualTo(SC_INTERNAL_SERVER_ERROR);
    assertThat(response.getPayload())
        .isEqualTo("FAILED Error occurred while updating registrar contacts.\n");
    assertThat(loadRegistrar("NewRegistrar").getContactsRequireSyncing()).isFalse();
    assertThat(loadRegistrar("TheRegistrar").getContactsRequireSyncing()).isTrue();
    assertThat(countLogsAtLevel(Level.SEVERE)).isEqualTo(1);
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
    assertThat(response.getStatus()).isEqualTo(SC_OK);
    assertThat(response.getPayload()).isEqualTo("OK Group memberships successfully updated.\n");
    assertThat(loadRegistrar("NewRegistrar").getContactsRequireSyncing()).isFalse();
  }

  @Test
  void test_doPost_invalidMemberKey400_doesNotRetryOrWedgeOtherGroupsOrEmitSevere()
      throws Exception {
    persistResource(
        loadRegistrar("TheRegistrar").asBuilder().setContactsRequireSyncing(false).build());
    persistResource(
        new RegistrarPoc.Builder()
            .setRegistrar(loadRegistrar("NewRegistrar"))
            .setName("Invalid Marketing")
            .setEmailAddress("contact@example.invalidtld")
            .setTypes(ImmutableSet.of(MARKETING))
            .build());
    persistResource(
        new RegistrarPoc.Builder()
            .setRegistrar(loadRegistrar("NewRegistrar"))
            .setName("Valid Marketing")
            .setEmailAddress("marketing@example.com")
            .setTypes(ImmutableSet.of(MARKETING))
            .build());
    persistResource(
        new RegistrarPoc.Builder()
            .setRegistrar(loadRegistrar("NewRegistrar"))
            .setName("Valid Tech")
            .setEmailAddress("tech@example.com")
            .setTypes(ImmutableSet.of(TECH))
            .build());
    doThrow(makeResponseException(SC_BAD_REQUEST, "Invalid Input: memberKey", "invalid"))
        .when(connection)
        .addMemberToGroup(
            eq("newregistrar-marketing-contacts@domain-registry.example"),
            eq("contact@example.invalidtld"),
            eq(Role.MEMBER));

    runAction();

    // Deterministic 400 Invalid Input: memberKey must NOT be retried.
    verify(connection, times(1))
        .addMemberToGroup(
            "newregistrar-marketing-contacts@domain-registry.example",
            "contact@example.invalidtld",
            Role.MEMBER);
    // Sibling contacts in the same group and subsequent contact types (TECH > MARKETING) must sync.
    verify(connection)
        .addMemberToGroup(
            "newregistrar-primary-contacts@domain-registry.example",
            "janedoe@theregistrar.com",
            Role.MEMBER);
    verify(connection)
        .addMemberToGroup(
            "newregistrar-marketing-contacts@domain-registry.example",
            "marketing@example.com",
            Role.MEMBER);
    verify(connection)
        .addMemberToGroup(
            "newregistrar-technical-contacts@domain-registry.example",
            "tech@example.com",
            Role.MEMBER);
    assertThat(response.getStatus()).isEqualTo(SC_OK);
    assertThat(loadRegistrar("NewRegistrar").getContactsRequireSyncing()).isFalse();
    assertThat(countLogsAtLevel(Level.SEVERE)).isEqualTo(0);
    assertThat(countLogsAtLevel(Level.WARNING)).isEqualTo(1);
    LogRecord warning =
        logHandler.getStoredLogRecords().stream()
            .filter(r -> r.getLevel().equals(Level.WARNING))
            .findFirst()
            .orElseThrow();
    assertThat(warning.getMessage()).contains("contact@example.invalidtld");
    assertThat(warning.getMessage()).contains("NewRegistrar");
    assertThat(warning.getMessage())
        .contains("newregistrar-marketing-contacts@domain-registry.example");
  }

  @Test
  void test_doPost_nonTransient403_doesNotRetry_isolatesOtherGroups_logsSevereOnce()
      throws Exception {
    persistResource(
        loadRegistrar("TheRegistrar").asBuilder().setContactsRequireSyncing(false).build());
    persistResource(
        new RegistrarPoc.Builder()
            .setRegistrar(loadRegistrar("NewRegistrar"))
            .setName("Marketing")
            .setEmailAddress("marketing@example.com")
            .setTypes(ImmutableSet.of(MARKETING))
            .build());
    persistResource(
        new RegistrarPoc.Builder()
            .setRegistrar(loadRegistrar("NewRegistrar"))
            .setName("Tech")
            .setEmailAddress("tech@example.com")
            .setTypes(ImmutableSet.of(TECH))
            .build());
    doThrow(makeResponseException(SC_FORBIDDEN, "Insufficient Permission", "forbidden"))
        .when(connection)
        .addMemberToGroup(
            eq("newregistrar-marketing-contacts@domain-registry.example"),
            eq("marketing@example.com"),
            eq(Role.MEMBER));

    runAction();

    verify(connection, times(1))
        .addMemberToGroup(
            "newregistrar-marketing-contacts@domain-registry.example",
            "marketing@example.com",
            Role.MEMBER);
    verify(connection)
        .addMemberToGroup(
            "newregistrar-technical-contacts@domain-registry.example",
            "tech@example.com",
            Role.MEMBER);
    assertThat(response.getStatus()).isEqualTo(SC_INTERNAL_SERVER_ERROR);
    assertThat(loadRegistrar("NewRegistrar").getContactsRequireSyncing()).isTrue();
    assertThat(countLogsAtLevel(Level.SEVERE)).isEqualTo(1);
  }

  @Test
  void test_doPost_wrappedMemberKey404_doesNotRetry_skipsAndContinues() throws Exception {
    persistResource(
        loadRegistrar("TheRegistrar").asBuilder().setContactsRequireSyncing(false).build());
    persistResource(
        new RegistrarPoc.Builder()
            .setRegistrar(loadRegistrar("NewRegistrar"))
            .setName("Tech")
            .setEmailAddress("tech@example.com")
            .setTypes(ImmutableSet.of(TECH))
            .build());
    doThrow(
            new RuntimeException(
                "Adding member janedoe@theregistrar.com to group failed because the member wasn't"
                    + " found.",
                makeResponseException(SC_NOT_FOUND, "Resource Not Found: memberKey", "notFound")))
        .when(connection)
        .addMemberToGroup(
            eq("newregistrar-primary-contacts@domain-registry.example"),
            eq("janedoe@theregistrar.com"),
            eq(Role.MEMBER));

    runAction();

    verify(connection, times(1))
        .addMemberToGroup(
            "newregistrar-primary-contacts@domain-registry.example",
            "janedoe@theregistrar.com",
            Role.MEMBER);
    verify(connection)
        .addMemberToGroup(
            "newregistrar-technical-contacts@domain-registry.example",
            "tech@example.com",
            Role.MEMBER);
    assertThat(response.getStatus()).isEqualTo(SC_OK);
    assertThat(loadRegistrar("NewRegistrar").getContactsRequireSyncing()).isFalse();
    assertThat(countLogsAtLevel(Level.SEVERE)).isEqualTo(0);
    assertThat(countLogsAtLevel(Level.WARNING)).isEqualTo(1);
  }

  @Test
  void test_doPost_retriesOnRateLimit429And403RateLimitExceeded() throws Exception {
    persistResource(
        loadRegistrar("TheRegistrar").asBuilder().setContactsRequireSyncing(false).build());
    doThrow(makeResponseException(SC_TOO_MANY_REQUESTS, "Too Many Requests", "rateLimitExceeded"))
        .doThrow(makeResponseException(SC_FORBIDDEN, "Quota Exceeded", "userRateLimitExceeded"))
        .doNothing()
        .when(connection)
        .addMemberToGroup(
            eq("newregistrar-primary-contacts@domain-registry.example"),
            eq("janedoe@theregistrar.com"),
            eq(Role.MEMBER));

    runAction();

    verify(connection, times(3))
        .addMemberToGroup(
            "newregistrar-primary-contacts@domain-registry.example",
            "janedoe@theregistrar.com",
            Role.MEMBER);
    assertThat(response.getStatus()).isEqualTo(SC_OK);
    assertThat(loadRegistrar("NewRegistrar").getContactsRequireSyncing()).isFalse();
    assertThat(countLogsAtLevel(Level.SEVERE)).isEqualTo(0);
  }

  @Test
  void test_doPost_removeMemberFailure_isolatesOtherRemovalsAndGroups_logsSevereOnce()
      throws Exception {
    persistResource(
        loadRegistrar("TheRegistrar").asBuilder().setContactsRequireSyncing(false).build());
    when(connection.getMembersOfGroup("newregistrar-primary-contacts@domain-registry.example"))
        .thenReturn(
            ImmutableSet.of(
                "stale1@example.com", "stale2@example.com", "janedoe@theregistrar.com"));
    persistResource(
        new RegistrarPoc.Builder()
            .setRegistrar(loadRegistrar("NewRegistrar"))
            .setName("Tech")
            .setEmailAddress("tech@example.com")
            .setTypes(ImmutableSet.of(TECH))
            .build());
    doThrow(makeResponseException(SC_FORBIDDEN, "Insufficient Permission", "forbidden"))
        .when(connection)
        .removeMemberFromGroup(
            "newregistrar-primary-contacts@domain-registry.example", "stale1@example.com");

    runAction();

    verify(connection, times(1))
        .removeMemberFromGroup(
            "newregistrar-primary-contacts@domain-registry.example", "stale1@example.com");
    verify(connection)
        .removeMemberFromGroup(
            "newregistrar-primary-contacts@domain-registry.example", "stale2@example.com");
    verify(connection)
        .addMemberToGroup(
            "newregistrar-technical-contacts@domain-registry.example",
            "tech@example.com",
            Role.MEMBER);
    assertThat(response.getStatus()).isEqualTo(SC_INTERNAL_SERVER_ERROR);
    assertThat(loadRegistrar("NewRegistrar").getContactsRequireSyncing()).isTrue();
    assertThat(countLogsAtLevel(Level.SEVERE)).isEqualTo(1);
  }

  @Test
  void test_doPost_removeMemberMissingMemberKey404_skipsWithWarningAndContinues() throws Exception {
    persistResource(
        loadRegistrar("TheRegistrar").asBuilder().setContactsRequireSyncing(false).build());
    when(connection.getMembersOfGroup("newregistrar-primary-contacts@domain-registry.example"))
        .thenReturn(
            ImmutableSet.of(
                "missing@example.invalidtld", "stale@example.com", "janedoe@theregistrar.com"));
    doThrow(makeResponseException(SC_NOT_FOUND, "Resource Not Found: memberKey", "notFound"))
        .when(connection)
        .removeMemberFromGroup(
            "newregistrar-primary-contacts@domain-registry.example", "missing@example.invalidtld");

    runAction();

    verify(connection, times(1))
        .removeMemberFromGroup(
            "newregistrar-primary-contacts@domain-registry.example", "missing@example.invalidtld");
    verify(connection)
        .removeMemberFromGroup(
            "newregistrar-primary-contacts@domain-registry.example", "stale@example.com");
    assertThat(response.getStatus()).isEqualTo(SC_OK);
    assertThat(loadRegistrar("NewRegistrar").getContactsRequireSyncing()).isFalse();
    assertThat(countLogsAtLevel(Level.SEVERE)).isEqualTo(0);
    assertThat(countLogsAtLevel(Level.WARNING)).isEqualTo(1);
  }

  private long countLogsAtLevel(Level level) {
    return logHandler.getStoredLogRecords().stream()
        .filter(record -> record.getLevel().equals(level))
        .count();
  }

  private static GoogleJsonResponseException makeResponseException(
      int statusCode, String message, String reason) throws IOException {
    HttpTransport transport =
        new MockHttpTransport() {
          @Override
          public LowLevelHttpRequest buildRequest(String method, String url) {
            return new MockLowLevelHttpRequest() {
              @Override
              public LowLevelHttpResponse execute() {
                MockLowLevelHttpResponse httpResponse = new MockLowLevelHttpResponse();
                httpResponse.setStatusCode(statusCode);
                httpResponse.setContentType(Json.MEDIA_TYPE);
                httpResponse.setContent(
                    String.format(
                        "{\"error\":{\"code\":%d,\"message\":\"%s\","
                            + "\"errors\":[{\"domain\":\"global\",\"reason\":\"%s\","
                            + "\"message\":\"%s\"}]}}",
                        statusCode, message, reason, message));
                return httpResponse;
              }
            };
          }
        };
    HttpRequest request =
        transport
            .createRequestFactory()
            .buildGetRequest(HttpTesting.SIMPLE_GENERIC_URL)
            .setThrowExceptionOnExecuteError(false);
    return GoogleJsonResponseException.from(new GsonFactory(), request.execute());
  }
}
