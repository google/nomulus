// Copyright 2020 The Nomulus Authors. All Rights Reserved.
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

import com.beust.jcommander.Parameters;
import com.googlecode.objectify.Key;
import google.registry.model.domain.DomainBase;
import google.registry.persistence.VKey;
import javax.annotation.Nullable;
import org.joda.time.DateTime;

/** Command to remove the Registry 1.0 key in {@link DomainBase} entity. */
@Parameters(separators = " =", commandDescription = "Remove .")
public class RemoveRegistryOneKeyCommand extends ReadEntityFromKeyPathCommand<DomainBase> {

  @Override
  void process(DomainBase entity) {
    // Assert that the DomainBase entity must be deleted before 2017-08-01(most of the problematic
    // entities were deleted before 2017, though there are still a few entities deleted in 2017-07).
    // This is because we finished the Registry 2.0 migration in 2017 and should not generate any
    // Registry 1.0 key after it.
    if (!entity.getDeletionTime().isBefore(DateTime.parse("2017-08-01T00:00:00Z"))) {
      throw new IllegalStateException(
          String.format(
              "Entity's deletion time %s is not before 2017-08-01T00:00:00Z",
              entity.getDeletionTime()));
    }
    boolean hasChange = false;
    DomainBase.Builder domainBuilder = entity.asBuilder();
    // We only found the registry 1.0 key existed in fields autorenewBillingEvent,
    // autorenewPollMessage and deletePollMessage so we just need to check these fields for each
    // entity.
    if (isRegistryOneKey(entity.getAutorenewBillingEvent())) {
      domainBuilder.setAutorenewBillingEvent(null);
      hasChange = true;
    }
    if (isRegistryOneKey(entity.getAutorenewPollMessage())) {
      domainBuilder.setAutorenewPollMessage(null);
      hasChange = true;
    }
    if (isRegistryOneKey(entity.getDeletePollMessage())) {
      domainBuilder.setDeletePollMessage(null);
      hasChange = true;
    }
    if (hasChange) {
      stageEntityChange(entity, domainBuilder.build());
    }
  }

  private static boolean isRegistryOneKey(@Nullable VKey<?> vKey) {
    if (vKey == null || vKey.getOfyKey() == null || vKey.getOfyKey().getParent() == null) {
      return false;
    }
    Key<?> parentKey = vKey.getOfyKey().getParent();
    return parentKey.getKind().equals("EntityGroupRoot") && parentKey.getName().equals("per-tld");
  }
}
