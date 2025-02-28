// Copyright 2018 The Nomulus Authors. All Rights Reserved.
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

package google.registry.rdap;

import static com.google.common.base.Preconditions.checkArgument;
import static google.registry.rdap.RdapAuthorization.Role.ADMINISTRATOR;
import static google.registry.rdap.RdapAuthorization.Role.PUBLIC;
import static google.registry.rdap.RdapAuthorization.Role.REGISTRAR;
import static google.registry.request.Action.Method.GET;
import static google.registry.request.Action.Method.HEAD;
import static org.mockito.Mockito.mock;

import com.google.gson.JsonObject;
import google.registry.model.console.User;
import google.registry.model.console.UserRoles;
import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.persistence.transaction.JpaTestExtensions.JpaIntegrationTestExtension;
import google.registry.request.Actions;
import google.registry.request.auth.AuthResult;
import google.registry.testing.FakeClock;
import google.registry.testing.FakeResponse;
import google.registry.util.Idn;
import google.registry.util.TypeUtils;
import java.util.HashMap;
import java.util.Optional;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Common unit test code for actions inheriting {@link RdapActionBase}. */
abstract class RdapActionBaseTestCase<A extends RdapActionBase> {

  protected final FakeClock clock = new FakeClock(DateTime.parse("2000-01-01TZ"));

  @RegisterExtension
  final JpaIntegrationTestExtension jpa =
      new JpaTestExtensions.Builder().withClock(clock).buildIntegrationTestExtension();

  protected static final AuthResult AUTH_RESULT =
      AuthResult.createUser(
          new User.Builder()
              .setEmailAddress("rdap.user@user.com")
              .setUserRoles(new UserRoles.Builder().setIsAdmin(false).build())
              .build());

  protected static final AuthResult AUTH_RESULT_ADMIN =
      AuthResult.createUser(
          new User.Builder()
              .setEmailAddress("rdap.admin@google.com")
              .setUserRoles(new UserRoles.Builder().setIsAdmin(true).build())
              .build());

  protected FakeResponse response = new FakeResponse();
  final RdapMetrics rdapMetrics = mock(RdapMetrics.class);

  RdapAuthorization.Role metricRole = PUBLIC;
  protected A action;

  final String actionPath;
  private final Class<A> rdapActionClass;

  RdapActionBaseTestCase(Class<A> rdapActionClass) {
    this.rdapActionClass = rdapActionClass;
    actionPath = Actions.getPathForAction(rdapActionClass);
  }

  @BeforeEach
  public void beforeEachRdapActionBaseTestCase() {
    action = TypeUtils.instantiate(rdapActionClass);
    action.includeDeletedParam = Optional.empty();
    action.formatOutputParam = Optional.empty();
    action.response = response;
    action.rdapJsonFormatter = RdapTestHelper.getTestRdapJsonFormatter(clock);
    action.rdapMetrics = rdapMetrics;
    action.requestMethod = GET;
    action.clock = new FakeClock(DateTime.parse("2025-01-01T00:00:00.000Z"));
    logout();
  }

  protected void login(String registrarId) {
    action.rdapAuthorization = RdapAuthorization.create(REGISTRAR, registrarId);
    action.rdapJsonFormatter.rdapAuthorization = action.rdapAuthorization;
    metricRole = REGISTRAR;
  }

  protected void logout() {
    action.rdapAuthorization = RdapAuthorization.PUBLIC_AUTHORIZATION;
    action.rdapJsonFormatter.rdapAuthorization = action.rdapAuthorization;
    metricRole = PUBLIC;
  }

  void loginAsAdmin() {
    action.rdapAuthorization = RdapAuthorization.ADMINISTRATOR_AUTHORIZATION;
    action.rdapJsonFormatter.rdapAuthorization = action.rdapAuthorization;
    metricRole = ADMINISTRATOR;
  }

  JsonObject generateActualJson(String domainName) {
    action.requestPath = actionPath + domainName;
    action.requestMethod = GET;
    action.run();
    return RdapTestHelper.parseJsonObject(response.getPayload());
  }

  String generateHeadPayload(String domainName) {
    action.requestPath = actionPath + domainName;
    action.requestMethod = HEAD;
    action.run();
    return response.getPayload();
  }

  JsonObject generateExpectedJsonError(String description, int code) {
    String title =
        switch (code) {
          case 404 -> "Not Found";
          case 500 -> "Internal Server Error";
          case 501 -> "Not Implemented";
          case 400 -> "Bad Request";
          case 422 -> "Unprocessable Entity";
          default -> "ERR";
        };
    return RdapTestHelper.loadJsonFile(
        "rdap_error.json",
        "DESCRIPTION",
        description,
        "TITLE",
        title,
        "CODE",
        String.valueOf(code));
  }

  static JsonFileBuilder jsonFileBuilder() {
    return new JsonFileBuilder();
  }

  protected static final class JsonFileBuilder {
    private final HashMap<String, String> substitutions = new HashMap<>();

    public JsonObject load(String filename) {
      return RdapTestHelper.loadJsonFile(filename, substitutions);
    }

    public JsonFileBuilder put(String key, String value) {
      checkArgument(
          substitutions.put(key, value) == null, "substitutions already had key of %s", key);
      return this;
    }

    public JsonFileBuilder put(String key, int index, String value) {
      return put(String.format("%s%d", key, index), value);
    }

    JsonFileBuilder putNext(String key, String value, String... moreKeyValues) {
      checkArgument(moreKeyValues.length % 2 == 0);
      int index = putNextAndReturnIndex(key, value);
      for (int i = 0; i < moreKeyValues.length; i += 2) {
        put(moreKeyValues[i], index, moreKeyValues[i + 1]);
      }
      return this;
    }

    JsonFileBuilder addDomain(String name, String handle) {
      return putNext(
          "DOMAIN_PUNYCODE_NAME_", Idn.toASCII(name),
          "DOMAIN_UNICODE_NAME_", name,
          "DOMAIN_HANDLE_", handle);
    }

    JsonFileBuilder addNameserver(String name, String handle) {
      return putNext(
          "NAMESERVER_NAME_", Idn.toASCII(name),
          "NAMESERVER_UNICODE_NAME_", name,
          "NAMESERVER_HANDLE_", handle);
    }

    JsonFileBuilder addRegistrar(String fullName) {
      return putNext("REGISTRAR_FULL_NAME_", fullName);
    }

    JsonFileBuilder addContact(String handle) {
      return putNext("CONTACT_HANDLE_", handle);
    }

    JsonFileBuilder setNextQuery(String nextQuery) {
      return put("NEXT_QUERY", nextQuery);
    }

    private int putNextAndReturnIndex(String key, String value) {
      for (int i = 1; ; i++) {
        if (substitutions.putIfAbsent(String.format("%s%d", key, i), value) == null) {
          return i;
        }
      }
    }
  }
}
