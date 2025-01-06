// Copyright 2025 The Nomulus Authors. All Rights Reserved.
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

package google.registry.testing;

import static com.google.common.truth.Truth.assertAbout;

import com.google.common.collect.Sets;
import com.google.common.truth.FailureMetadata;
import com.google.common.truth.SimpleSubjectBuilder;
import com.google.common.truth.Subject;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Objects;
import java.util.Optional;

/** A Truth subject to show nicer "huge JSON" diffs. */
public class GsonSubject extends Subject {

  private JsonObject actual;

  private static final Gson GSON =
      new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

  private String customDisplaySubject;

  public GsonSubject(FailureMetadata failureMetadata, JsonObject actual) {
    super(failureMetadata, actual);
    this.actual = actual;
  }

  @Override
  protected String actualCustomStringRepresentation() {
    return Optional.ofNullable(customDisplaySubject).orElse(String.valueOf(actual));
  }

  public GsonSubject withCustomDisplaySubject(String customDisplaySubject) {
    this.customDisplaySubject = customDisplaySubject;
    return this;
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
  private void diff(String name, JsonElement actual, JsonElement expected, StringBuilder builder) {
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
            "Actual: %s -> %s\nExpected: %s\n\n",
            name, jsonifyAndIndent(actual), jsonifyAndIndent(expected)));
  }

  public static SimpleSubjectBuilder<GsonSubject, JsonObject> assertAboutJson() {
    return assertAbout(jsonObject());
  }

  public static Factory<GsonSubject, JsonObject> jsonObject() {
    return GsonSubject::new;
  }
}
