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

import static google.registry.model.ofy.ObjectifyService.auditedOfy;
import static google.registry.model.reporting.HistoryEntryDao.REPO_ID_FIELD_NAMES;
import static google.registry.model.reporting.HistoryEntryDao.RESOURCE_TYPES_TO_HISTORY_TYPES;
import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;
import static google.registry.persistence.transaction.TransactionManagerFactory.ofyTm;

import com.google.appengine.tools.mapreduce.Mapper;
import com.google.common.collect.ImmutableList;
import com.googlecode.objectify.Key;
import google.registry.config.RegistryConfig.Config;
import google.registry.mapreduce.MapreduceRunner;
import google.registry.mapreduce.inputs.EppResourceInputs;
import google.registry.model.EppResource;
import google.registry.model.domain.DomainHistory;
import google.registry.model.reporting.HistoryEntry;
import google.registry.persistence.transaction.CriteriaQueryBuilder;
import google.registry.rde.RdeStagingAction;
import google.registry.request.Action;
import google.registry.request.Response;
import google.registry.request.auth.Auth;
import google.registry.tools.server.GenerateZoneFilesAction;
import java.util.Optional;
import javax.inject.Inject;
import javax.persistence.criteria.CriteriaBuilder;
import javax.persistence.criteria.CriteriaQuery;

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
 * history object for which the EppResource field is filled. This synthetic history object contains
 * basically nothing and its only purpose is to create a populated history object in SQL through
 * asynchronous replication.
 *
 * <p>NB: This class operates entirely in Datastore, which may be counterintuitive at first glance.
 * However, since this is meant to be run during the Datastore-primary, SQL-secondary stage of the
 * migration, we want to make sure that we are using the most up-to-date version of the data. The
 * resource field of the history objects will be populated during asynchronous migration, e.g. in
 * {@link DomainHistory#beforeSqlSaveOnReplay}.
 */
@Action(
    service = Action.Service.BACKEND,
    path = "/_dr/task/createSyntheticHistoryEntries",
    auth = Auth.AUTH_INTERNAL_OR_ADMIN)
public class CreateSyntheticHistoryEntriesAction implements Runnable {

  private static final String HISTORY_REASON =
      "Backfill EppResource history objects during Cloud SQL migration";

  private final MapreduceRunner mrRunner;
  private final Response response;
  private final String registryAdminRegistrarId;

  @Inject
  CreateSyntheticHistoryEntriesAction(
      MapreduceRunner mrRunner,
      Response response,
      @Config("registryAdminClientId") String registryAdminRegistrarId) {
    this.mrRunner = mrRunner;
    this.response = response;
    this.registryAdminRegistrarId = registryAdminRegistrarId;
  }

  /**
   * The number of shards to run the map-only mapreduce on.
   *
   * <p>This is less than the default of 100 because we can afford it being slower, but we don't
   * want to write out lots of large commit logs in a short period of time. If we did so, the
   * asynchronous replication action (run every few minutes) might fall behind which may make the
   * migration tougher.
   */
  private static final int NUM_SHARDS = 10;

  @Override
  public void run() {
    mrRunner
        .setJobName("Create a synthetic HistoryEntry for each EPP resource")
        .setModuleName("backend")
        .setDefaultMapShards(NUM_SHARDS)
        .runMapOnly(
            new CreateSyntheticHistoryEntriesMapper(registryAdminRegistrarId),
            ImmutableList.of(EppResourceInputs.createKeyInput(EppResource.class)))
        .sendLinkToMapreduceConsole(response);
  }

  // Lifted from HistoryEntryDao
  private static Optional<? extends HistoryEntry> mostRecentHistoryFromSql(EppResource resource) {
    return jpaTm()
        .transact(
            () -> {
              // The class we're searching from is based on which parent type (e.g. Domain) we have
              Class<? extends HistoryEntry> historyClass =
                  getHistoryClassFromParent(resource.getClass());
              // The field representing repo ID unfortunately varies by history class
              String repoIdFieldName = getRepoIdFieldNameFromHistoryClass(historyClass);
              CriteriaBuilder criteriaBuilder = jpaTm().getEntityManager().getCriteriaBuilder();
              CriteriaQuery<? extends HistoryEntry> criteriaQuery =
                  CriteriaQueryBuilder.create(historyClass)
                      .where(repoIdFieldName, criteriaBuilder::equal, resource.getRepoId())
                      .orderByDesc("modificationTime")
                      .build();
              return jpaTm()
                  .criteriaQuery(criteriaQuery)
                  .setMaxResults(1)
                  .getResultStream()
                  .findFirst();
            });
  }

  // Lifted from HistoryEntryDao
  private static Class<? extends HistoryEntry> getHistoryClassFromParent(
      Class<? extends EppResource> parent) {
    if (!RESOURCE_TYPES_TO_HISTORY_TYPES.containsKey(parent)) {
      throw new IllegalArgumentException(
          String.format("Unknown history type for parent %s", parent.getName()));
    }
    return RESOURCE_TYPES_TO_HISTORY_TYPES.get(parent);
  }

  // Lifted from HistoryEntryDao
  private static String getRepoIdFieldNameFromHistoryClass(
      Class<? extends HistoryEntry> historyClass) {
    if (!REPO_ID_FIELD_NAMES.containsKey(historyClass)) {
      throw new IllegalArgumentException(
          String.format("Unknown history type %s", historyClass.getName()));
    }
    return REPO_ID_FIELD_NAMES.get(historyClass);
  }

  /** Mapper to re-save all EPP resources. */
  public static class CreateSyntheticHistoryEntriesMapper
      extends Mapper<Key<EppResource>, Void, Void> {

    private final String registryAdminRegistrarId;

    public CreateSyntheticHistoryEntriesMapper(String registryAdminRegistrarId) {
      this.registryAdminRegistrarId = registryAdminRegistrarId;
    }

    @Override
    public final void map(final Key<EppResource> resourceKey) {
      EppResource eppResource = auditedOfy().load().key(resourceKey).now();
      // Only save new history entries if the most recent history for this object in SQL does not
      // have the resource at that point in time already
      Optional<? extends HistoryEntry> maybeHistory = mostRecentHistoryFromSql(eppResource);
      if (maybeHistory
          .map(history -> !history.getResourceAtPointInTime().isPresent())
          .orElse(true)) {
        ofyTm()
            .transact(
                () ->
                    ofyTm()
                        .put(
                            HistoryEntry.createBuilderForResource(eppResource)
                                .setRegistrarId(registryAdminRegistrarId)
                                .setBySuperuser(true)
                                .setRequestedByRegistrar(false)
                                .setModificationTime(ofyTm().getTransactionTime())
                                .setReason(HISTORY_REASON)
                                .setType(HistoryEntry.Type.SYNTHETIC)
                                .build()));
      }
    }
  }
}
