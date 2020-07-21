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

package google.registry.tools;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static google.registry.request.JsonResponse.JSON_SAFETY_PREFIX;
import static google.registry.tools.server.ListObjectsAction.FIELDS_PARAM;
import static google.registry.tools.server.ListObjectsAction.FULL_FIELD_NAMES_PARAM;
import static google.registry.tools.server.ListObjectsAction.PRINT_HEADER_ROW_PARAM;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.net.MediaType;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

/** Abstract base class for unit tests of commands that list object data using a back-end task. */
public abstract class ListObjectsCommandTestCase<C extends ListObjectsCommand>
    extends CommandTestCase<C> {

  @Mock AppEngineConnection connection;

  /** Where to find the servlet task; set by the subclass. */
  abstract String getTaskPath();

  /** The other parameters to be used (for those subclasses that use them; defaults to empty). */
  protected ImmutableMap<String, Object> getOtherParameters() {
    return ImmutableMap.of();
  }

  private ImmutableList<String> otherParams = ImmutableList.of();

  @BeforeEach
  void beforeEachListObjectsCommandTestCase() throws Exception {
    ImmutableMap<String, Object> otherParameters = getOtherParameters();
    if (!otherParameters.isEmpty()) {
      otherParams =
          otherParameters.entrySet().stream()
              .map(entry -> String.format("--%s=%s", entry.getKey(), entry.getValue()))
              .collect(toImmutableList());
    }
    command.setConnection(connection);
    when(connection.sendPostRequest(
            eq(getTaskPath()), anyMap(), eq(MediaType.PLAIN_TEXT_UTF_8), any(byte[].class)))
        .thenReturn(JSON_SAFETY_PREFIX + "{\"status\":\"success\",\"lines\":[]}");
  }

  private void verifySent(
      String fields, Optional<Boolean> printHeaderRow, Optional<Boolean> fullFieldNames)
      throws Exception {

    ImmutableMap.Builder<String, Object> params = new ImmutableMap.Builder<>();
    if (fields != null) {
      params.put(FIELDS_PARAM, fields);
    }
    printHeaderRow.ifPresent(aBoolean -> params.put(PRINT_HEADER_ROW_PARAM, aBoolean));
    fullFieldNames.ifPresent(aBoolean -> params.put(FULL_FIELD_NAMES_PARAM, aBoolean));
    params.putAll(getOtherParameters());
    verify(connection)
        .sendPostRequest(
            eq(getTaskPath()), eq(params.build()), eq(MediaType.PLAIN_TEXT_UTF_8), eq(new byte[0]));
  }

  @Test
  void testRun_noFields() throws Exception {
    runCommand(otherParams);
    verifySent(null, Optional.empty(), Optional.empty());
  }

  @Test
  void testRun_oneField() throws Exception {
    runCommand(
        new ImmutableList.Builder<String>().addAll(otherParams).add("--fields=fieldName").build());
    verifySent("fieldName", Optional.empty(), Optional.empty());
  }

  @Test
  void testRun_wildcardField() throws Exception {
    runCommand(new ImmutableList.Builder<String>().addAll(otherParams).add("--fields=*").build());
    verifySent("*", Optional.empty(), Optional.empty());
  }

  @Test
  void testRun_header() throws Exception {
    runCommand(
        new ImmutableList.Builder<String>()
            .addAll(otherParams)
            .add("--fields=fieldName", "--header=true")
            .build());
    verifySent("fieldName", Optional.of(Boolean.TRUE), Optional.empty());
  }

  @Test
  void testRun_noHeader() throws Exception {
    runCommand(
        new ImmutableList.Builder<String>()
            .addAll(otherParams)
            .add("--fields=fieldName", "--header=false")
            .build());
    verifySent("fieldName", Optional.of(Boolean.FALSE), Optional.empty());
  }

  @Test
  void testRun_fullFieldNames() throws Exception {
    runCommand(
        new ImmutableList.Builder<String>()
            .addAll(otherParams)
            .add("--fields=fieldName", "--full_field_names")
            .build());
    verifySent("fieldName", Optional.empty(), Optional.of(Boolean.TRUE));
  }

  @Test
  void testRun_allParameters() throws Exception {
    runCommand(
        new ImmutableList.Builder<String>()
            .addAll(otherParams)
            .add("--fields=fieldName,otherFieldName,*", "--header=true", "--full_field_names")
            .build());
    verifySent("fieldName,otherFieldName,*", Optional.of(Boolean.TRUE), Optional.of(Boolean.TRUE));
  }
}
