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

package google.registry.rdap;

import static com.google.common.base.Preconditions.checkArgument;
import static google.registry.testing.TestDataHelper.loadFile;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Sets;
import com.google.common.collect.Streams;
import com.google.common.truth.Truth;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import google.registry.util.Clock;
import java.util.Map;
import java.util.Objects;

/** Test helper methods for RDAP tests. */
class RdapTestHelper {

  private static final Gson GSON =
      new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

  static JsonElement createJson(String... lines) {
    return GSON.fromJson(Joiner.on("\n").join(lines), JsonElement.class);
  }

  enum ContactNoticeType {
    NONE,
    DOMAIN,
    CONTACT
  }

  private static JsonObject createTosNotice(String linkBase) {
    return GSON.toJsonTree(
        ImmutableMap.of(
            "title", "RDAP Terms of Service",
            "description",
                ImmutableList.of(
                    "By querying our Domain Database, you are agreeing to comply with these terms"
                        + " so please read them carefully.",
                    "Any information provided is 'as is' without any guarantee of accuracy.",
                    "Please do not misuse the Domain Database. It is intended solely for"
                        + " query-based access.",
                    "Don't use the Domain Database to allow, enable, or otherwise support the"
                        + " transmission of mass unsolicited, commercial advertising or"
                        + " solicitations.",
                    "Don't access our Domain Database through the use of high volume, automated"
                        + " electronic processes that send queries or data to the systems of any"
                        + " ICANN-accredited registrar.",
                    "You may only use the information contained in the Domain Database for lawful"
                        + " purposes.",
                    "Do not compile, repackage, disseminate, or otherwise use the information"
                        + " contained in the Domain Database in its entirety, or in any substantial"
                        + " portion, without our prior written permission.",
                    "We may retain certain details about queries to our Domain Database for the"
                        + " purposes of detecting and preventing misuse.",
                    "We reserve the right to restrict or deny your access to the database if we"
                        + " suspect that you have failed to comply with these terms.",
                    "We reserve the right to modify this agreement at any time."),
            "links",
                ImmutableList.of(
                    ImmutableMap.of(
                        "rel", "self",
                        "href", linkBase + "help/tos",
                        "type", "application/rdap+json"),
                    ImmutableMap.of(
                        "rel", "alternate",
                        "href", "https://www.registry.tld/about/rdap/tos.html",
                        "type", "text/html")))).getAsJsonObject();
  }

  static void addNonDomainBoilerplateNotices(JsonObject jsonObject, String linkBase) {
    if (!jsonObject.has("notices")) {
      jsonObject.add("notices", new JsonArray());
    }
    JsonArray notices = jsonObject.getAsJsonArray("notices");

    notices.add(createTosNotice(linkBase));
    notices.add(
        GSON.toJsonTree(
            ImmutableMap.of(
                "description",
                ImmutableList.of(
                    "This response conforms to the RDAP Operational Profile for gTLD"
                        + " Registries and Registrars version 1.0"))));
  }

  static void addDomainBoilerplateNotices(JsonObject jsonObject, String linkBase) {
    if (!jsonObject.has("notices")) {
      jsonObject.add("notices", new JsonArray());
    }
    JsonArray notices = jsonObject.getAsJsonArray("notices");

    notices.add(createTosNotice(linkBase));
    notices.add(
        GSON.toJsonTree(
            ImmutableMap.of(
                "description",
                ImmutableList.of(
                    "This response conforms to the RDAP Operational Profile for gTLD"
                        + " Registries and Registrars version 1.0"))));
    notices.add(
        GSON.toJsonTree(
            ImmutableMap.of(
                "title",
                "Status Codes",
                "description",
                ImmutableList.of(
                    "For more information on domain status codes, please visit"
                        + " https://icann.org/epp"),
                "links",
                ImmutableList.of(
                    ImmutableMap.of(
                        "rel", "alternate",
                        "href", "https://icann.org/epp",
                        "type", "text/html")))));
    notices.add(
        GSON.toJsonTree(
            ImmutableMap.of(
                "title",
                "RDDS Inaccuracy Complaint Form",
                "description",
                ImmutableList.of(
                    "URL of the ICANN RDDS Inaccuracy Complaint Form:"
                        + " https://www.icann.org/wicf"),
                "links",
                ImmutableList.of(
                    ImmutableMap.of(
                        "rel", "alternate",
                        "href", "https://www.icann.org/wicf",
                        "type", "text/html")))));
  }

  static RdapJsonFormatter getTestRdapJsonFormatter(Clock clock) {
    RdapJsonFormatter rdapJsonFormatter = new RdapJsonFormatter();
    rdapJsonFormatter.rdapAuthorization = RdapAuthorization.PUBLIC_AUTHORIZATION;
    rdapJsonFormatter.fullServletPath = "https://example.tld/rdap/";
    rdapJsonFormatter.clock = clock;
    rdapJsonFormatter.rdapTos =
        ImmutableList.of(
            "By querying our Domain Database, you are agreeing to comply with these"
                + " terms so please read them carefully.",
            "Any information provided is 'as is' without any guarantee of accuracy.",
            "Please do not misuse the Domain Database. It is intended solely for"
                + " query-based access.",
            "Don't use the Domain Database to allow, enable, or otherwise support the"
                + " transmission of mass unsolicited, commercial advertising or"
                + " solicitations.",
            "Don't access our Domain Database through the use of high volume, automated"
                + " electronic processes that send queries or data to the systems of"
                + " any ICANN-accredited registrar.",
            "You may only use the information contained in the Domain Database for"
                + " lawful purposes.",
            "Do not compile, repackage, disseminate, or otherwise use the information"
                + " contained in the Domain Database in its entirety, or in any"
                + " substantial portion, without our prior written permission.",
            "We may retain certain details about queries to our Domain Database for the"
                + " purposes of detecting and preventing misuse.",
            "We reserve the right to restrict or deny your access to the database if we"
                + " suspect that you have failed to comply with these terms.",
            "We reserve the right to modify this agreement at any time.");
    rdapJsonFormatter.rdapTosStaticUrl = "https://www.registry.tld/about/rdap/tos.html";
    return rdapJsonFormatter;
  }

  static String getLinkToNext(JsonObject results) {
    JsonArray notices = results.getAsJsonArray("notices");
    Truth.assertThat(notices).isNotNull();
    return Streams.stream(notices)
        .map(notice -> notice.getAsJsonObject())
        .filter(notice -> notice.has("title"))
        .filter(notice -> notice.get("title").getAsString().equals("Navigation Links"))
        .flatMap(notice -> Streams.stream(notice.getAsJsonArray("links")))
        .map(link -> link.getAsJsonObject())
        .filter(link -> link.get("rel").getAsString().equals("next"))
        .map(link -> link.get("href").getAsString())
        .findAny().orElse(null);
  }

  /**
   * Loads a resource testdata JSON file, and applies substitutions.
   *
   * <p>{@code loadJsonFile("filename.json", "NANE", "something", "ID", "other")} is the same as
   * {@code loadJsonFile("filename.json", ImmutableMap.of("NANE", "something", "ID", "other"))}.
   *
   * @param filename the name of the file from the testdata directory
   * @param keysAndValues alternating substitution key and value. The substitutions are applied to
   *     the file before parsing it to JSON.
   */
  static JsonObject loadJsonFile(String filename, String... keysAndValues) {
    checkArgument(keysAndValues.length % 2 == 0);
    ImmutableMap.Builder<String, String> builder = new ImmutableMap.Builder<>();
    for (int i = 0; i < keysAndValues.length; i += 2) {
      if (keysAndValues[i + 1] != null) {
        builder.put(keysAndValues[i], keysAndValues[i + 1]);
      }
    }
    return loadJsonFile(filename, builder.build());
  }

  /**
   * Loads a resource testdata JSON file, and applies substitutions.
   *
   * @param filename the name of the file from the testdata directory
   * @param substitutions map of substitutions to apply to the file. The substitutions are applied
   *     to the file before parsing it to JSON.
   */
  static JsonObject loadJsonFile(String filename, Map<String, String> substitutions) {
    System.out.format("Loading JSON file: %s\n", filename);
    return parseJsonObject(loadFile(RdapTestHelper.class, filename, substitutions));
  }

  static JsonObject parseJsonObject(String jsonString) {
    return GSON.fromJson(jsonString, JsonObject.class);
  }

  static JsonObject wrapInSearchReply(String searchResultsName, JsonObject obj) {
    JsonArray searchResults = new JsonArray();
    searchResults.add(obj);
    JsonObject reply = new JsonObject();

    reply.add(searchResultsName, searchResults);
    reply.add("rdapConformance", obj.getAsJsonArray("rdapConformance"));
    obj.remove("rdapConformance");
    return reply;
  }

  /** A small utility class to show nicer "huge JSON" diffs. */
  static final class GsonSubject {
    private JsonObject actual;

    GsonSubject(JsonObject actual) {
      this.actual = actual;
    }

    void isEqualTo(JsonObject expected) {
      if (actual.equals(expected)) {
        return;
      }
      StringBuilder difference = new StringBuilder();
      difference.append("Actual JSON different than expected:");
      diff("", actual, expected, difference);
      throw new AssertionError(difference.toString());
    }

    private String jsonifyAndIndent(JsonElement element) {
      String json = GSON.toJson(element);
      return json.replaceAll("\n", "\n    ");
    }

    private JsonElement getOrNull(JsonArray jsonArray, int index) {
      if (index >= jsonArray.size()) {
        return null;
      }
      return jsonArray.get(index);
    }

    /** Writes down a human-readable diff between actual and expected into the StringBuilder. */
    private void diff(
        String name, JsonElement actual, JsonElement expected, StringBuilder builder) {
      if (Objects.equals(actual, expected)) {
        return;
      }
      if (actual == null) {
        builder.append(String.format("Missing: %s ->%s\n\n", name, jsonifyAndIndent(expected)));
        return;
      }
      if (expected == null) {
        builder.append(String.format("Unexpected: %s -> %s\n\n", name, jsonifyAndIndent(actual)));
        return;
      }
      if (actual.isJsonObject() && expected.isJsonObject()) {
        // We put the "expected" first in the union so that the "expected" keys will all be first
        // and in order
        for (String key :
            Sets.union(expected.getAsJsonObject().keySet(), actual.getAsJsonObject().keySet())) {
          diff(
              name + "." + key,
              actual.getAsJsonObject().get(key),
              expected.getAsJsonObject().get(key),
              builder);
        }
        return;
      }
      if (actual.isJsonArray() && expected.isJsonArray()) {
        int commonSize = Math.max(actual.getAsJsonArray().size(), expected.getAsJsonArray().size());
        for (int i = 0; i < commonSize; i++) {
          diff(
              String.format("%s[%s]", name, i),
              getOrNull(actual.getAsJsonArray(), i),
              getOrNull(expected.getAsJsonArray(), i),
              builder);
        }
        return;
      }
      builder.append(
          String.format(
              "Different: %s -> %s\ninstead of %s\n\n",
              name, jsonifyAndIndent(actual), jsonifyAndIndent(expected)));
    }
  }

  static GsonSubject assertThat(JsonObject actual) {
    return new GsonSubject(actual);
  }
}
