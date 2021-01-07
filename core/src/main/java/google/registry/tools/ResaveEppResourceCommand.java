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

import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.util.PreconditionsUtils.checkArgumentNotNull;
import static org.joda.time.DateTimeZone.UTC;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import google.registry.model.EppResource;
import google.registry.persistence.VKey;
import google.registry.tools.CommandUtilities.ResourceType;
import org.joda.time.DateTime;

/**
 * A command to load and resave an {@link EppResource} by foreign key.
 *
 * <p>This triggers @OnSave changes. If the entity was directly edited in the Datastore viewer, this
 * can be used to make sure that the commit logs reflect the new state.
 */
@Parameters(
    separators = " =",
    commandDescription = "Load and resave EPP resources by foreign key")
public final class ResaveEppResourceCommand extends MutatingCommand {

  @Parameter(
    names = "--type",
    description = "Resource type.")
  protected ResourceType type;

  @Parameter(
    names = "--id",
    description = "Foreign key of the resource.")
  protected String uniqueId;

  @Override
  protected void init() {
    VKey<? extends EppResource> resourceKey =
        checkArgumentNotNull(
            type.getKey(uniqueId, DateTime.now(UTC)),
            "Could not find active resource of type %s: %s",
            type,
            uniqueId);
    // Load the resource directly to bypass running cloneProjectedAtTime() automatically, which can
    // cause stageEntityChange() to fail due to implicit projection changes.
    EppResource resource = tm().loadByKey(resourceKey);
    stageEntityChange(resource, resource);
  }
}
