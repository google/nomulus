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

package google.registry.tools;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import google.registry.request.RouterDisplayHelper;

/** Generates the routing map file used for unit testing. */
@Parameters(commandDescription = "Generate a routing map file")
final class GetRoutingMapCommand implements Command {

  @Parameter(
    names = {"-c", "--class"},
    description =
        "Request component class (e.g. google.registry.module.backend.BackendRequestComponent)"
        + " for which routing map should be generated",
    required = true
  )
  private String serviceClassName;

  @Override
  public void run() throws Exception {
    System.out.println(
        RouterDisplayHelper.extractHumanReadableRoutesFromComponent(
            Class.forName(serviceClassName)));
  }
}
