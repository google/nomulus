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

import static com.google.common.base.Strings.isNullOrEmpty;
import static google.registry.security.JsonHttp.JSON_SAFETY_PREFIX;
import static google.registry.tools.server.CreateOrUpdatePremiumListAction.INPUT_PARAM;
import static google.registry.tools.server.CreateOrUpdatePremiumListAction.NAME_PARAM;
import static google.registry.util.ListNamingUtils.convertFilePathToName;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.beust.jcommander.Parameter;
import com.google.common.base.Joiner;
import com.google.common.base.Verify;
import com.google.common.collect.ImmutableMap;
import com.google.common.net.MediaType;
import google.registry.model.registry.label.PremiumList;
import google.registry.tools.params.PathParameter;
import java.net.URLEncoder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import org.json.simple.JSONValue;

/**
 * Base class for specification of command line parameters common to creating and updating premium
 * lists.
 */
abstract class CreateOrUpdatePremiumListCommand extends ConfirmingCommand
    implements CommandWithConnection, CommandWithRemoteApi {

  @Nullable
  @Parameter(
      names = {"-n", "--name"},
      description = "The name of this premium list (defaults to filename if not specified). "
          + "This is almost always the name of the TLD this premium list will be used on.")
  String name;

  @Parameter(
      names = {"-i", "--input"},
      description = "Filename of premium list to create or update.",
      validateWith = PathParameter.InputFile.class,
      required = true)
  Path inputFile;

  protected AppEngineConnection connection;
  protected int inputLineCount;

  @Override
  public void setConnection(AppEngineConnection connection) {
    this.connection = connection;
  }

  abstract String getCommandPath();

  ImmutableMap<String, String> getParameterMap() {
    return ImmutableMap.of();
  }

  @Override
  protected void init() throws Exception {
    name = isNullOrEmpty(name) ? convertFilePathToName(inputFile) : name;
    List<String> lines = Files.readAllLines(inputFile, UTF_8);
    // Try constructing and parsing the premium list locally to check up front for validation errors
    new PremiumList.Builder().setName(name).build().parse(lines);
    inputLineCount = lines.size();
  }

  @Override
  protected String prompt() {
    return String.format(
        "You are about to save the premium list %s with %d items: ", name, inputLineCount);
  }

  @Override
  public String execute() throws Exception {
    ImmutableMap.Builder<String, String> params = new ImmutableMap.Builder<>();
    params.put(NAME_PARAM, name);
    String inputFileContents = new String(Files.readAllBytes(inputFile), UTF_8);
    String requestBody =
        Joiner.on('&').withKeyValueSeparator("=").join(
            ImmutableMap.of(INPUT_PARAM, URLEncoder.encode(inputFileContents, UTF_8.toString())));

    ImmutableMap<String, String> extraParams = getParameterMap();
    if (extraParams != null) {
      params.putAll(extraParams);
    }

    // Call the server and get the response data
    String response =
        connection.sendPostRequest(
            getCommandPath(), params.build(), MediaType.FORM_DATA, requestBody.getBytes(UTF_8));

    return extractServerResponse(response);
  }

  // TODO(user): refactor this behavior into a better general-purpose
  // response validation that can be re-used across the new client/server commands.
  private String extractServerResponse(String response) {
    Map<String, Object> responseMap = toMap(JSONValue.parse(stripJsonPrefix(response)));

    // TODO(user): consider using jart's FormField Framework.
    // See: j/c/g/d/r/ui/server/RegistrarFormFields.java
    String status = (String) responseMap.get("status");
    Verify.verify(!status.equals("error"), "Server error: %s", responseMap.get("error"));
    return String.format("Successfully saved premium list %s\n", name);
  }

  @SuppressWarnings("unchecked")
  static Map<String, Object> toMap(Object obj) {
    Verify.verify(obj instanceof Map<?, ?>, "JSON object is not a Map: %s", obj);
    return (Map<String, Object>) obj;
  }

  // TODO(user): figure out better place to put this method to make it re-usable
  private static String stripJsonPrefix(String json) {
    Verify.verify(json.startsWith(JSON_SAFETY_PREFIX));
    return json.substring(JSON_SAFETY_PREFIX.length());
  }
}
