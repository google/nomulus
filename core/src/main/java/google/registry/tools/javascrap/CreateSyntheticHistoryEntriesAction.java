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

package google.registry.tools.javascrap;

import static google.registry.model.ofy.ObjectifyService.ofy;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;

import com.google.appengine.tools.mapreduce.Mapper;
import com.google.common.collect.ImmutableList;
import com.googlecode.objectify.Key;
import google.registry.mapreduce.MapreduceRunner;
import google.registry.mapreduce.inputs.EppResourceInputs;
import google.registry.model.EppResource;
import google.registry.model.domain.DomainHistory;
import google.registry.model.reporting.HistoryEntry;
import google.registry.rde.RdeStagingAction;
import google.registry.request.Action;
import google.registry.request.Response;
import google.registry.request.auth.Auth;
import google.registry.tools.server.GenerateZoneFilesAction;
import javax.inject.Inject;

/**
 * A mapreduce that creates synthetic history objects in SQL for all {@link EppResource} objects.
 *
 * <p>Certain operations, e.g. {@link RdeStagingAction} or {@link GenerateZoneFilesAction}, require
 * that we are able to answer the question of "What did this EPP resource look like at a point in
 * time?" In the Datastore world, we are able to answer this question using the commit logs, however
 * this is no longer possible in the SQL world. Instead, we will use the history objects, e.g.
 * {@link DomainHistory} to see what a particular resource looked like at that point in time, since
 * history objects store a snapshot of that resource.
 *
 * <p>This command creates a synthetic history object at the current point in time for every single
 * EPP resource to guarantee that later on, when examining in-the-past resources, we have some
 * history object for which the EppResource field is filled.
 *
 * <p>NB: This class operates entirely in Datastore, which may be counterintuitive at first glance.
 * However, since this is meant to be run during the Datastore-primary, SQL-secondary stage of the
 * migration, we want to make sure that we are using the most up to date version of the data. The
 * resource field of the history objects will be populated during asynchronous migration, e.g. in
 * {@link DomainHistory#beforeSqlSave(DomainHistory)}.
 */
@Action(
    service = Action.Service.BACKEND,
    path = "/_dr/task/createSyntheticHistoryEntries",
    auth = Auth.AUTH_INTERNAL_OR_ADMIN)
public class CreateSyntheticHistoryEntriesAction implements Runnable {

  @Inject MapreduceRunner mrRunner;
  @Inject Response response;

  @Inject
  CreateSyntheticHistoryEntriesAction() {}

  /**
   * The number of shards to run the map-only mapreduce on.
   *
   * <p>This is less than the default of 100 because we can afford it being slower, but we don't
   * want to write out lots of large commit logs in a short period of time because they make the
   * Cloud SQL migration tougher.
   */
  private static final int NUM_SHARDS = 10;

  @Override
  public void run() {
    mrRunner
        .setJobName("Create a synthetic HistoryEntry for each EPP resource")
        .setModuleName("backend")
        .setDefaultMapShards(NUM_SHARDS)
        .runMapOnly(
            new CreateSyntheticHistoryEntriesMapper(),
            ImmutableList.of(EppResourceInputs.createKeyInput(EppResource.class)))
        .sendLinkToMapreduceConsole(response);
  }

  /** Mapper to re-save all EPP resources. */
  public static class CreateSyntheticHistoryEntriesMapper
      extends Mapper<Key<EppResource>, Void, Void> {

    @Override
    public final void map(final Key<EppResource> resourceKey) {
      tm().transact(
              () -> {
                EppResource resource = ofy().load().key(resourceKey).now();
                tm().put(
                        new HistoryEntry.Builder<>()
                            .setClientId(resource.getPersistedCurrentSponsorClientId())
                            .setBySuperuser(true)
                            .setRequestedByRegistrar(false)
                            .setModificationTime(tm().getTransactionTime())
                            .setParent(resource)
                            .setReason("Synthetic history entry")
                            .setType(HistoryEntry.Type.SYNTHETIC)
                            .build());
              });
    }
  }
}
