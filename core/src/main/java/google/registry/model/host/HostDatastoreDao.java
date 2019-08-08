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

package google.registry.model.host;

import static google.registry.flows.ResourceFlowUtils.loadAndVerifyExistence;
import static google.registry.model.EppResourceUtils.checkResourcesExist;
import static google.registry.model.index.ForeignKeyIndex.loadAndGetKey;
import static google.registry.model.ofy.ObjectifyService.ofy;

import com.google.common.collect.ImmutableSet;
import com.googlecode.objectify.Key;
import google.registry.flows.ResourceFlowUtils.ResourceDoesNotExistException;
import google.registry.model.ImmutableObject;
import google.registry.model.index.EppResourceIndex;
import google.registry.model.index.ForeignKeyIndex;
import java.util.Optional;
import java.util.Set;
import org.joda.time.DateTime;

/** Datastore implementation of {@link HostDao}. */
public class HostDatastoreDao implements HostDao {

  @Override
  public void save(HostResource hostResource) {
    ofy().save().entity(hostResource);
  }

  @Override
  public HostResource findByFqhn(String fqhn, DateTime time) throws ResourceDoesNotExistException {
    return loadAndVerifyExistence(HostResource.class, fqhn, time);
  }

  @Override
  public boolean checkExistsByFqhn(String fqhn, DateTime now) {
    return loadAndGetKey(HostResource.class, fqhn, now) != null;
  }

  @Override
  public Set<String> checkExistsByFqhn(Iterable<String> fqhns, DateTime now) {
    return checkResourcesExist(HostResource.class, fqhns, now);
  }

  @Override
  public void updateIndex(
      HostResource newHost, DateTime now, Optional<HostResource> maybeExistingHost) {
    ImmutableSet.Builder<ImmutableObject> entitiesToSave = new ImmutableSet.Builder<>();
    entitiesToSave.add(ForeignKeyIndex.create(newHost, newHost.getDeletionTime()));
    if (maybeExistingHost.isPresent()) {
      HostResource existingHost = maybeExistingHost.get();
      if (!existingHost.getFullyQualifiedHostName().equals(newHost.getFullyQualifiedHostName())) {
        entitiesToSave.add(ForeignKeyIndex.create(existingHost, now));
      }
    } else {
      entitiesToSave.add(EppResourceIndex.create(Key.create(newHost)));
    }
    ofy().save().entities(entitiesToSave.build());
  }
}
