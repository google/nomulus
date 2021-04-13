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

import static com.google.common.base.Verify.verify;
import static google.registry.model.ofy.ObjectifyService.ofy;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.googlecode.objectify.Key;
import google.registry.model.contact.ContactResource;
import google.registry.model.index.ForeignKeyIndex;
import google.registry.model.ofy.ObjectifyService;
import google.registry.util.SystemClock;
import java.util.List;
import java.util.Objects;

/**
 * Deletes a {@link google.registry.model.contact.ContactResource} by its ROID.
 *
 * <p>This is a short-term tool for race condition clean up while the bug is being fixed.
 */
@Parameters(separators = " =", commandDescription = "Delete a contact by its ROID.")
public class DeleteContactByRoidCommand extends ConfirmingCommand implements CommandWithRemoteApi {

  @Parameter(names = "--roid", description = "The roid of the contact to be deleted.")
  String roid;

  @Parameter(
      names = "--contact_id",
      description = "The user provided contactId, for verification purpose.")
  String contactId;

  ImmutableList<Key<?>> toDelete;

  @Override
  protected void init() throws Exception {
    System.out.printf("Deleting %s, which refers to %s.\n", roid, contactId);
    ObjectifyService.initOfy();
    Key<ContactResource> targetKey = Key.create(ContactResource.class, roid);
    ContactResource contact = ofy().load().key(targetKey).now();
    verify(Objects.equals(contact.getContactId(), contactId), "contactId does not match.");
    System.out.println("Target contact has the expected contactId");
    String canonicalResource =
        ForeignKeyIndex.load(ContactResource.class, contactId, new SystemClock().nowUtc())
            .getResourceKey()
            .getOfyKey()
            .getName();
    verify(!Objects.equals(canonicalResource, roid), "Contact still in ForeignKeyIndex.");
    System.out.printf(
        "It is safe to delete %s, since the contactId is mapped to a different entry in the"
            + " Foreign key index (%s).\n\n",
        roid, canonicalResource);

    List<Object> ancestors = ofy().load().ancestor(Key.create(ContactResource.class, roid)).list();

    System.out.println("Ancestor query returns: ");
    for (Object entity : ancestors) {
      System.out.println(Key.create(entity));
    }

    ImmutableSet<String> deletetableKinds = ImmutableSet.of("HistoryEntry", "ContactResource");
    toDelete =
        ancestors.stream()
            .map(Key::create)
            .filter(key -> deletetableKinds.contains(key.getKind()))
            .collect(ImmutableList.toImmutableList());
    System.out.printf("\n\nAbout to delete %s entities:\n", toDelete.size());
    toDelete.forEach(key -> System.out.println(key));
  }

  @Override
  protected String execute() {
    tm().transact(() -> ofy().delete().keys(toDelete).now());
    return "Done";
  }
}
