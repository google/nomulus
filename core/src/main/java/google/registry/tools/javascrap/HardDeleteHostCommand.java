// Copyright 2021 The Nomulus Authors. All Rights Reserved.
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

package google.registry.tools.javascrap;

import static com.google.common.base.Verify.verify;
import static google.registry.model.ofy.ObjectifyService.auditedOfy;
import static google.registry.persistence.transaction.TransactionManagerFactory.ofyTm;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.google.common.collect.ImmutableList;
import com.googlecode.objectify.Key;
import google.registry.model.host.HostResource;
import google.registry.model.index.EppResourceIndex;
import google.registry.model.index.ForeignKeyIndex;
import google.registry.tools.CommandWithRemoteApi;
import google.registry.tools.ConfirmingCommand;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Deletes a {@link HostResource} by its ROID.
 *
 * <p>This deletes the host itself, everything in the same entity group including all {@link
 * google.registry.model.reporting.HistoryEntry}s and {@link
 * google.registry.model.poll.PollMessage}s, the {@link EppResourceIndex}, and the {@link
 * ForeignKeyIndex} (if it exists).
 *
 * <p>DO NOT use this to hard-delete a host that is still in use on a domain. Bad things will
 * happen.
 */
@Parameters(separators = " =", commandDescription = "Delete a host by its ROID.")
public class HardDeleteHostCommand extends ConfirmingCommand implements CommandWithRemoteApi {

  @Parameter(names = "--roid", description = "The ROID of the host to be deleted.")
  String roid;

  @Parameter(names = "--hostname", description = "The hostname, for verification.")
  String hostname;

  private ImmutableList<Key<Object>> toDelete;

  @Override
  protected void init() {
    ofyTm()
        .transact(
            () -> {
              Key<HostResource> targetKey = Key.create(HostResource.class, roid);
              HostResource host = auditedOfy().load().key(targetKey).now();
              verify(Objects.equals(host.getHostName(), hostname), "Hostname does not match");

              List<Key<Object>> objectsInEntityGroup =
                  auditedOfy().load().ancestor(host).keys().list();

              Optional<ForeignKeyIndex<HostResource>> fki =
                  Optional.ofNullable(
                      auditedOfy().load().key(ForeignKeyIndex.createKey(host)).now());
              if (!fki.isPresent()) {
                System.out.println(
                    "No ForeignKeyIndex exists, likely because resource is soft-deleted."
                        + " Continuing.");
              }

              EppResourceIndex eppResourceIndex =
                  auditedOfy().load().entity(EppResourceIndex.create(targetKey)).now();
              verify(eppResourceIndex.getKey().equals(targetKey), "Wrong EppResource Index loaded");

              ImmutableList.Builder<Key<Object>> toDeleteBuilder =
                  new ImmutableList.Builder<Key<Object>>()
                      .addAll(objectsInEntityGroup)
                      .add(Key.create(eppResourceIndex));
              fki.ifPresent(f -> toDeleteBuilder.add(Key.create(f)));
              toDelete = toDeleteBuilder.build();

              System.out.printf("\n\nAbout to delete %d entities with keys:\n", toDelete.size());
              toDelete.forEach(System.out::println);
            });
  }

  @Override
  protected String execute() {
    tm().transact(() -> auditedOfy().delete().keys(toDelete).now());
    return "Done.";
  }
}
