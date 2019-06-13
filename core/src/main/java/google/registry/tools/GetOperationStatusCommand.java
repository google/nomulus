// Copyright 2019 The Nomulus Authors. All Rights Reserved.
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

import static com.google.common.base.Preconditions.checkArgument;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.common.base.Strings;
import google.registry.export.datastore.DatastoreAdmin;
import java.util.List;
import javax.inject.Inject;

/** Command to get the status of a Datastore operation, e.g., an import or export. */
@Parameters(separators = " =", commandDescription = "Get status of a Datastore operation.")
public class GetOperationStatusCommand implements Command {

  private static final JsonFactory JSON_FACTORY = JacksonFactory.getDefaultInstance();

  @Parameter(description = "Name of the Datastore import or export operation.")
  private List<String> mainParameters;

  @Inject DatastoreAdmin datastoreAdmin;

  @Override
  public void run() throws Exception {
    checkArgument(
        mainParameters.size() == 1, "Requires exactly one argument: the name of the operation.");
    String operationName = mainParameters.get(0);
    checkArgument(!Strings.isNullOrEmpty(operationName), "Missing operation name.");
    System.out.println(JSON_FACTORY.toPrettyString(datastoreAdmin.get(operationName).execute()));
  }
}
