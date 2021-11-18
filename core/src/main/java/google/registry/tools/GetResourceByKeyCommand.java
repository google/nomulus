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

import static com.google.common.base.Preconditions.checkNotNull;
import static google.registry.model.ofy.ObjectifyService.auditedOfy;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import google.registry.model.EppResource;
import google.registry.persistence.VKey;
import java.util.List;

/**
 * Command to get info on a Datastore resource by websafe key.
 */
@Parameters(separators = " =", commandDescription = "Fetch a Datastore resource by websafe key")
final class GetResourceByKeyCommand implements CommandWithRemoteApi {

  @Parameter(
      description = "Websafe key string(s)",
      required = true)
  private List<String> mainParameters;

  @Parameter(
      names = "--expand",
      description = "Fully expand the requested resource. NOTE: Output may be lengthy.")
  boolean expand;

  @Override
  public void run() throws Exception {
    for (String keyString : mainParameters) {
      System.out.println("\n");
      VKey<EppResource> resourceKey =
          checkNotNull(VKey.create(keyString), "Could not parse key string: " + keyString);
      EppResource resource =
          checkNotNull(
              auditedOfy().load().key(resourceKey.getOfyKey()).now(),
              "Could not load resource for key: " + resourceKey.getOfyKey());
      System.out.println(expand ? resource.toHydratedString() : resource.toString());
    }
  }
}
