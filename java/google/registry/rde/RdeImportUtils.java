// Copyright 2016 The Domain Registry Authors. All Rights Reserved.
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

package google.registry.rde;

import com.google.inject.Inject;

import com.googlecode.objectify.Key;
import com.googlecode.objectify.Work;
import google.registry.model.contact.ContactResource;
import google.registry.model.index.EppResourceIndex;
import google.registry.model.index.ForeignKeyIndex;
import google.registry.model.ofy.Ofy;

/**
 * Utility functions for escrow file import.
 */
public final class RdeImportUtils {

  private final Ofy ofy;

  @Inject
  public RdeImportUtils(Ofy ofy) {
    this.ofy = ofy;
  }

  /**
   * Imports a contact from an escrow file.
   *
   * <p>The contact will only be imported if one of the following two conditions is satisfied:
   * <ul>
   * <li>The contact has not been previously imported
   * <li>The previously imported contact has an older eppUpdateTime than the new one.
   * </ul>
   *
   * <p>If the contact is imported, ForeignKeyIndex and EppResourceIndex are also updated.
   *
   * @return true if the contact was created or updated, false otherwise.
   */
  public boolean importContact(final ContactResource resource) {
    return ofy.transact(new Work<Boolean>() {

      @Override
      public Boolean run() {
        ContactResource existing = ofy.load().key(Key.create(resource)).now();
        if (existing == null
            || existing.getLastEppUpdateTime().isBefore(resource.getLastEppUpdateTime())) {
          ofy.save().entity(resource);
          ofy.save().entity(ForeignKeyIndex.create(resource, resource.getDeletionTime()));
          ofy.save().entity(EppResourceIndex.create(Key.create(resource)));
          return true;
        }
        return false;
      }
    });
  }
}
