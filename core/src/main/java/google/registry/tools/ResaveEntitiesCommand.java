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

import static com.google.common.collect.Lists.partition;
import static google.registry.model.ofy.ObjectifyService.auditedOfy;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.googlecode.objectify.Key;
import google.registry.model.ImmutableObject;
import java.util.List;

/**
 * A command to load and resave an entity by websafe key.
 *
 * <p>This triggers @OnSave changes. If the entity was directly edited in the Datastore viewer, this
 * can be used to make sure that the commit logs reflect the new state.
 */
@Parameters(
    separators = " =",
    commandDescription = "Load and resave entities by websafe key")
public final class ResaveEntitiesCommand extends MutatingCommand {

  /** The number of resaves to do in a single transaction. */
  private static final int BATCH_SIZE = 10;

  @Parameter(description = "Websafe keys", required = true)
  List<String> mainParameters;

  @Override
  protected void init() {
    for (List<String> batch : partition(mainParameters, BATCH_SIZE)) {
      for (String websafeKey : batch) {
        ImmutableObject entity =
            auditedOfy().load().key(Key.<ImmutableObject>create(websafeKey)).now();
        stageEntityChange(entity, entity);
      }
      flushTransaction();
    }
  }
}
