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

package google.registry.tools.server;

import static com.google.common.truth.Truth.assertThat;
import static javax.servlet.http.HttpServletResponse.SC_OK;

import google.registry.testing.AppEngineRule;
import google.registry.testing.FakeJsonResponse;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Base class for tests of list actions.
 */
public class ListActionTestCase {

  @RegisterExtension
  public final AppEngineRule appEngine = AppEngineRule.builder().withDatastoreAndCloudSql().build();

  private FakeJsonResponse response;

  private void runAction(
      ListObjectsAction<?> action,
      Optional<String> fields,
      Optional<Boolean> printHeaderRow,
      Optional<Boolean> fullFieldNames) {
    response = new FakeJsonResponse();
    action.response = response;
    action.fields = fields;
    action.printHeaderRow = printHeaderRow;
    action.fullFieldNames = fullFieldNames;
    action.run();
  }

  void testRunSuccess(
      ListObjectsAction<?> action,
      Optional<String> fields,
      Optional<Boolean> printHeaderRow,
      Optional<Boolean> fullFieldNames,
      String ... expectedLinePatterns) {
    assertThat(expectedLinePatterns).isNotNull();
    runAction(action, fields, printHeaderRow, fullFieldNames);
    assertThat(response.getStatus()).isEqualTo(SC_OK);
    assertThat(response.getResponseMap().get("status")).isEqualTo("success");
    Object obj = response.getResponseMap().get("lines");
    assertThat(obj).isInstanceOf(List.class);
    @SuppressWarnings("unchecked")
    List<String> lines = (List<String>) obj;
    assertThat(lines).hasSize(expectedLinePatterns.length);
    for (int i = 0; i < lines.size(); i++) {
      assertThat(lines.get(i)).containsMatch(Pattern.compile(expectedLinePatterns[i]));
    }
  }

  void testRunError(
      ListObjectsAction<?> action,
      Optional<String> fields,
      Optional<Boolean> printHeaderRow,
      Optional<Boolean> fullFieldNames,
      String expectedErrorPattern) {
    assertThat(expectedErrorPattern).isNotNull();
    runAction(action, fields, printHeaderRow, fullFieldNames);
    assertThat(response.getStatus()).isEqualTo(SC_OK);
    assertThat(response.getResponseMap().get("status")).isEqualTo("error");
    Object obj = response.getResponseMap().get("error");
    assertThat(obj).isInstanceOf(String.class);
    String error = obj.toString();
    assertThat(error).containsMatch(Pattern.compile(expectedErrorPattern));
  }
}
