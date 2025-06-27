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

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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
import javax.annotation.Nullable;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Common unit test code for actions inheriting {@link RdapActionBase}. */
abstract class RdapActionBaseTestCase<A extends RdapActionBase> {

  protected final FakeClock clock = new FakeClock(DateTime.parse("2000-01-01TZ"));
  static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

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

  JsonObject generateActualJson(String name) {
    return RdapTestHelper.parseJsonObject(runAction(name));
  }

  String generateHeadPayload(String name) {
    action.requestMethod = HEAD;
    return runAction(name);
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
        String.valueOf(code),
        "REQUEST_URL",
        action.requestUrl);
  }

  JsonFileBuilder jsonFileBuilder() {
    return new JsonFileBuilder(action.requestUrl);
  }

  private String runAction(String name) {
    action.requestPath = actionPath + name;
    action.requestUrl = "https://example.tld" + actionPath + name;
    action.run();
    return response.getPayload();
  }

  JsonElement createTosNotice() {
    return JsonParser.parseString(
"""
{
  "title": "RDAP Terms of Service",
  "description": [
    "By querying our Domain Database, you are agreeing to comply with these terms so please read \
them carefully.",
    "Any information provided is 'as is' without any guarantee of accuracy.",
    "Please do not misuse the Domain Database. It is intended solely for query-based access.",
    "Don't use the Domain Database to allow, enable, or otherwise support the transmission of mass \
unsolicited, commercial advertising or solicitations.",
    "Don't access our Domain Database through the use of high volume, automated electronic \
processes that send queries or data to the systems of any ICANN-accredited registrar.",
    "You may only use the information contained in the Domain Database for lawful purposes.",
    "Do not compile, repackage, disseminate, or otherwise use the information contained in the \
Domain Database in its entirety, or in any substantial portion, without our prior written \
permission.",
    "We may retain certain details about queries to our Domain Database for the purposes of \
detecting and preventing misuse.",
    "We reserve the right to restrict or deny your access to the database if we suspect that you \
have failed to comply with these terms.",
    "We reserve the right to modify this agreement at any time."
  ],
  "links": [
    {
      "rel": "self",
      "href": "https://example.tld/rdap/help/tos",
      "type": "application/rdap+json",
      "value": "%REQUEST_URL%"
    },
    {
      "rel": "terms-of-service",
      "href": "https://www.example.tld/about/rdap/tos.html",
      "type": "text/html",
      "value": "%REQUEST_URL%"
    }
  ]
}
"""
            .replaceAll("%REQUEST_URL%", action.requestUrl));
  }

  JsonObject addPermanentBoilerplateNotices(JsonObject jsonObject) {
    if (!jsonObject.has("notices")) {
      jsonObject.add("notices", new JsonArray());
    }
    JsonArray notices = jsonObject.getAsJsonArray("notices");
    notices.add(createTosNotice());
    return jsonObject;
  }

  JsonObject addDomainBoilerplateNotices(JsonObject jsonObject) {
    addPermanentBoilerplateNotices(jsonObject);
    JsonArray notices = jsonObject.getAsJsonArray("notices");
    notices.add(
        JsonParser.parseString(
"""
{
  "title": "Status Codes",
  "description": [
    "For more information on domain status codes, please visit https://icann.org/epp"
  ],
  "links": [
    {
      "rel": "glossary",
      "href": "https://icann.org/epp",
      "type": "text/html",
      "value": "%REQUEST_URL%"
    }
  ]
}
"""
                .replaceAll("%REQUEST_URL%", action.requestUrl)));
    notices.add(
        JsonParser.parseString(
"""
{
  "title": "RDDS Inaccuracy Complaint Form",
  "description": [
    "URL of the ICANN RDDS Inaccuracy Complaint Form: https://icann.org/wicf"
  ],
  "links": [
    {
      "rel": "help",
      "href": "https://icann.org/wicf",
      "type": "text/html",
      "value": "%REQUEST_URL%"
    }
  ]
}
"""
                .replaceAll("%REQUEST_URL%", action.requestUrl)));
    return jsonObject;
  }

  protected static final class JsonFileBuilder {
    private final HashMap<String, String> substitutions = new HashMap<>();

    private JsonFileBuilder(String requestUrl) {
      substitutions.put("REQUEST_URL", requestUrl);
    }

    public JsonObject load(String filename) {
      return RdapTestHelper.loadJsonFile(filename, substitutions);
    }

    public JsonFileBuilder put(String key, String value) {
      checkArgument(
          substitutions.put(key, value) == null, "substitutions already had key of %s", key);
      return this;
    }

    public JsonFileBuilder putAll(String... keysAndValues) {
      checkArgument(keysAndValues.length % 2 == 0);
      for (int i = 0; i < keysAndValues.length; i += 2) {
        put(keysAndValues[i], keysAndValues[i + 1]);
      }
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

    JsonFileBuilder addFullRegistrar(
        String handle, @Nullable String fullName, String status, @Nullable String address) {
      if (fullName != null) {
        putNext("REGISTRAR_FULLNAME_", fullName);
      }
      if (address != null) {
        putNext("REGISTRAR_ADDRESS_", address);
      }
      return putNext("REGISTRAR_HANDLE_", handle, "STATUS_", status);
    }

    JsonFileBuilder addContact(String handle) {
      return putNext("CONTACT_HANDLE_", handle);
    }

    JsonFileBuilder addFullContact(
        String handle,
        @Nullable String status,
        @Nullable String fullName,
        @Nullable String address) {
      if (fullName != null) {
        putNext("CONTACT_FULLNAME_", fullName);
      }
      if (address != null) {
        putNext("CONTACT_ADDRESS_", address);
      }
      if (status != null) {
        putNext("STATUS_", status);
      }
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
