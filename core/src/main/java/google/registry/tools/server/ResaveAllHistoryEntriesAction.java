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

package google.registry.tools.server;

import static google.registry.model.ofy.ObjectifyService.auditedOfy;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;

import com.google.appengine.tools.mapreduce.Mapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import google.registry.mapreduce.MapreduceRunner;
import google.registry.mapreduce.inputs.EppResourceInputs;
import google.registry.model.EppResource;
import google.registry.model.reporting.HistoryEntry;
import google.registry.request.Action;
import google.registry.request.Response;
import google.registry.request.auth.Auth;
import javax.inject.Inject;

/**
 * A mapreduce that re-saves all {@link HistoryEntry} entities.
 *
 * <p>This is useful for completing data migrations on HistoryEntry fields.
 *
 * <p>Because there are no auth settings in the {@link Action} annotation, this command can only be
 * run internally, or by pretending to be internal by setting the X-AppEngine-QueueName header,
 * which only admin users can do.
 */
@Action(
    service = Action.Service.TOOLS,
    path = "/_dr/task/resaveAllHistoryEntries",
    auth = Auth.AUTH_INTERNAL_OR_ADMIN)
public class ResaveAllHistoryEntriesAction implements Runnable {

  @Inject MapreduceRunner mrRunner;
  @Inject Response response;
  @Inject ResaveAllHistoryEntriesAction() {}

  @Override
  public void run() {
    mrRunner
        .setJobName("Re-save all HistoryEntry entities")
        .setModuleName("tools")
        .runMapOnly(
            new ResaveAllHistoryEntriesActionMapper(),
            ImmutableList.of(
                EppResourceInputs.createChildEntityInput(
                    ImmutableSet.of(EppResource.class), ImmutableSet.of(HistoryEntry.class))))
        .sendLinkToMapreduceConsole(response);
  }

  /** Mapper to re-save all HistoryEntry entities. */
  public static class ResaveAllHistoryEntriesActionMapper
      extends Mapper<HistoryEntry, Void, Void> {

    private static final long serialVersionUID = 123064872315192L;

    @Override
    public final void map(final HistoryEntry historyEntry) {
      tm().transact(
              () ->
                  auditedOfy().save().entity(auditedOfy().load().entity(historyEntry).now()).now());
      getContext().incrementCounter(
          String.format(
              "HistoryEntries parented under %s re-saved", historyEntry.getParent().getKind()));
    }
  }
}

