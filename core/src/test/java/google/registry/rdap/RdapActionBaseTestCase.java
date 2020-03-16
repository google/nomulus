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

import com.google.appengine.api.users.User;
import com.google.gson.JsonObject;
import google.registry.model.ofy.Ofy;
import google.registry.request.Action;
import google.registry.request.Actions;
import google.registry.request.auth.AuthLevel;
import google.registry.request.auth.AuthResult;
import google.registry.request.auth.UserAuthInfo;
import google.registry.testing.AppEngineRule;
import google.registry.testing.FakeClock;
import google.registry.testing.FakeResponse;
import google.registry.testing.InjectRule;
import google.registry.util.Idn;
import google.registry.util.TypeUtils;
import java.util.HashMap;
import java.util.Optional;
import org.joda.time.DateTime;
import org.junit.Before;
import org.junit.Rule;

/** Common unit test code for actions inheriting {@link RdapActionBase}. */
public abstract class RdapActionBaseTestCase<A extends RdapActionBase> {

  @Rule
  public final AppEngineRule appEngine = AppEngineRule.builder().withDatastoreAndCloudSql().build();

  @Rule
  public final InjectRule inject = new InjectRule();

  protected static final AuthResult AUTH_RESULT =
      AuthResult.create(
          AuthLevel.USER,
          UserAuthInfo.create(new User("rdap.user@user.com", "gmail.com", "12345"), false));

  protected static final AuthResult AUTH_RESULT_ADMIN =
      AuthResult.create(
          AuthLevel.USER,
          UserAuthInfo.create(new User("rdap.admin@google.com", "gmail.com", "12345"), true));

  protected FakeResponse response = new FakeResponse();
  protected final FakeClock clock = new FakeClock(DateTime.parse("2000-01-01TZ"));
  protected final RdapMetrics rdapMetrics = mock(RdapMetrics.class);

  protected RdapAuthorization.Role metricRole = PUBLIC;
  protected A action;

  protected final String actionPath;
  protected final Class<A> rdapActionClass;

  protected RdapActionBaseTestCase(Class<A> rdapActionClass) {
    this.rdapActionClass = rdapActionClass;
    this.actionPath = Actions.getPathForAction(rdapActionClass);
  }

  @Before
  public void baseSetUp() {
    inject.setStaticField(Ofy.class, "clock", clock);
    action = TypeUtils.instantiate(rdapActionClass);
    action.includeDeletedParam = Optional.empty();
    action.formatOutputParam = Optional.empty();
    action.response = response;
    action.rdapJsonFormatter = RdapTestHelper.getTestRdapJsonFormatter(clock);
    action.rdapMetrics = rdapMetrics;
    action.requestMethod = Action.Method.GET;
    logout();
  }

  protected void login(String clientId) {
    action.rdapAuthorization = RdapAuthorization.create(REGISTRAR, clientId);
    action.rdapJsonFormatter.rdapAuthorization = action.rdapAuthorization;
    metricRole = REGISTRAR;
  }

  protected void logout() {
    action.rdapAuthorization = RdapAuthorization.PUBLIC_AUTHORIZATION;
    action.rdapJsonFormatter.rdapAuthorization = action.rdapAuthorization;
    metricRole = PUBLIC;
  }

  protected void loginAsAdmin() {
    action.rdapAuthorization = RdapAuthorization.ADMINISTRATOR_AUTHORIZATION;
    action.rdapJsonFormatter.rdapAuthorization = action.rdapAuthorization;
    metricRole = ADMINISTRATOR;
  }

  protected JsonObject generateActualJson(String domainName) {
    action.requestPath = actionPath + domainName;
    action.requestMethod = GET;
    action.run();
    return RdapTestHelper.parseJsonObject(response.getPayload());
  }

  protected String generateHeadPayload(String domainName) {
    action.requestPath = actionPath + domainName;
    action.requestMethod = HEAD;
    action.run();
    return response.getPayload();
  }

  protected JsonObject generateExpectedJsonError(
      String description,
      int code) {
    String title;
    switch (code) {
      case 404:
        title = "Not Found";
        break;
      case 500:
        title = "Internal Server Error";
        break;
      case 501:
        title = "Not Implemented";
        break;
      case 400:
        title = "Bad Request";
        break;
      case 422:
        title = "Unprocessable Entity";
        break;
      default:
        title = "ERR";
        break;
    }
    return RdapTestHelper.loadJsonFile(
        "rdap_error.json",
        "DESCRIPTION", description,
        "TITLE", title,
        "CODE", String.valueOf(code));
  }

  protected static JsonFileBuilder jsonFileBuilder() {
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

    public JsonFileBuilder putNext(String key, String value, String... moreKeyValues) {
      checkArgument(moreKeyValues.length % 2 == 0);
      int index = putNextAndReturnIndex(key, value);
      for (int i = 0; i < moreKeyValues.length; i += 2) {
        put(moreKeyValues[i], index, moreKeyValues[i + 1]);
      }
      return this;
    }

    public JsonFileBuilder addDomain(String name, String handle) {
      return putNext(
          "DOMAIN_PUNYCODE_NAME_", Idn.toASCII(name),
          "DOMAIN_UNICODE_NAME_", name,
          "DOMAIN_HANDLE_", handle);
    }

    public JsonFileBuilder addNameserver(String name, String handle) {
      return putNext(
          "NAMESERVER_NAME_", Idn.toASCII(name),
          "NAMESERVER_UNICODE_NAME_", name,
          "NAMESERVER_HANDLE_", handle);
    }

    public JsonFileBuilder addRegistrar(String fullName) {
      return putNext("REGISTRAR_FULL_NAME_", fullName);
    }

    public JsonFileBuilder addContact(String handle) {
      return putNext("CONTACT_HANDLE_", handle);
    }

    public JsonFileBuilder setNextQuery(String nextQuery) {
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
