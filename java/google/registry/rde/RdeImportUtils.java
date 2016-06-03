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


import static com.google.common.base.Preconditions.checkState;

import com.googlecode.objectify.Key;
import com.googlecode.objectify.Work;
import google.registry.model.contact.ContactResource;
import google.registry.model.index.EppResourceIndex;
import google.registry.model.index.ForeignKeyIndex;
import google.registry.model.ofy.Ofy;
import google.registry.util.Clock;
import google.registry.util.FormattingLogger;

import javax.inject.Inject;

/**
 * Utility functions for escrow file import.
 */
public final class RdeImportUtils {

  private static final FormattingLogger logger = FormattingLogger.getLoggerForCallerClass();

  private final Ofy ofy;
  private final Clock clock;

  @Inject
  public RdeImportUtils(Ofy ofy, Clock clock) {
    this.ofy = ofy;
    this.clock = clock;
  }

  /**
   * Imports a contact from an escrow file.
   *
   * <p>The contact will only be imported if it has not been previously imported.
   *
   * <p>If the contact is imported, {@link ForeignKeyIndex} and {@link EppResourceIndex}
   * are also created.
   *
   * @return true if the contact was created or updated, false otherwise.
   */
  public boolean importContact(final ContactResource resource) {
    return ofy.transact(new Work<Boolean>() {

      @Override
      public Boolean run() {
        ContactResource existing = ofy.load().key(Key.create(resource)).now();
        if (existing == null) {
          ForeignKeyIndex<ContactResource> existingForeignKeyIndex =
              ForeignKeyIndex.load(ContactResource.class, resource.getContactId(), clock.nowUtc());
          // foreign key index should not exist, since existing contact was not found.
          checkState(existingForeignKeyIndex == null,
              String.format(
                  "New contact resource has existing foreign key index. contactId=%s, repoId=%s",
                  resource.getContactId(), resource.getRepoId()));
          ofy.save().entity(resource);
          ofy.save().entity(ForeignKeyIndex.create(resource, resource.getDeletionTime()));
          ofy.save().entity(EppResourceIndex.create(Key.create(resource)));
          logger.infofmt("Imported contact resource - ROID=%s, id=%s",
              resource.getRepoId(), resource.getContactId());
          return true;
        } else if (!existing.getRepoId().equals(resource.getRepoId())) {
          logger.warningfmt(
              "Existing contact with same contact id but different ROID. "
              + "contactId=%s, existing ROID=%s, new ROID=%s",
              resource.getContactId(), existing.getRepoId(), resource.getRepoId());
        }
        return false;
      }
    });
  }
}
