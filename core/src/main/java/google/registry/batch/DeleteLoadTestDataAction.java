// Copyright 2018 The Nomulus Authors. All Rights Reserved.
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

package google.registry.batch;

import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static google.registry.config.RegistryEnvironment.PRODUCTION;
import static google.registry.mapreduce.MapreduceRunner.PARAM_DRY_RUN;
import static google.registry.mapreduce.inputs.EppResourceInputs.createEntityInput;
import static google.registry.model.ofy.ObjectifyService.auditedOfy;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.request.Action.Method.POST;
import static google.registry.util.DateTimeUtils.END_OF_TIME;

import com.google.appengine.tools.mapreduce.Mapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.flogger.FluentLogger;
import com.googlecode.objectify.Key;
import google.registry.config.RegistryEnvironment;
import google.registry.flows.poll.PollFlowUtils;
import google.registry.mapreduce.MapreduceRunner;
import google.registry.model.EppResource;
import google.registry.model.EppResourceUtils;
import google.registry.model.contact.ContactResource;
import google.registry.model.domain.DomainBase;
import google.registry.model.host.HostResource;
import google.registry.model.index.EppResourceIndex;
import google.registry.model.index.ForeignKeyIndex;
import google.registry.model.poll.PollMessage;
import google.registry.model.reporting.HistoryEntry;
import google.registry.model.reporting.HistoryEntryDao;
import google.registry.persistence.VKey;
import google.registry.request.Action;
import google.registry.request.Parameter;
import google.registry.request.Response;
import google.registry.request.auth.Auth;
import google.registry.util.Clock;
import java.util.List;
import javax.inject.Inject;

/**
 * Hard deletes load-test ContactResources, HostResources, their subordinate history entries, and
 * the associated ForeignKey and EppResourceIndex entities.
 *
 * <p>This only deletes contacts and hosts, NOT domains. To delete domains, use {@link
 * DeleteProberDataAction} and pass it the TLD(s) that the load test domains were created on. Note
 * that DeleteProberDataAction is safe enough to run in production whereas this mapreduce is not,
 * but this one does not need to be runnable in production because load testing isn't run against
 * production.
 */
@Action(
    service = Action.Service.BACKEND,
    path = "/_dr/task/deleteLoadTestData",
    method = POST,
    auth = Auth.AUTH_INTERNAL_OR_ADMIN)
public class DeleteLoadTestDataAction implements Runnable {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  /**
   * The registrars for which to wipe out all contacts/hosts.
   *
   * <p>This is hard-coded because it's too dangerous to specify as a request parameter. By putting
   * it in code it always has to go through code review.
   */
  private static final ImmutableSet<String> LOAD_TEST_REGISTRARS = ImmutableSet.of("proxy");

  private final boolean isDryRun;
  private final MapreduceRunner mrRunner;
  private final Response response;
  private final Clock clock;

  @Inject
  DeleteLoadTestDataAction(
      @Parameter(PARAM_DRY_RUN) boolean isDryRun,
      MapreduceRunner mrRunner,
      Response response,
      Clock clock) {
    this.isDryRun = isDryRun;
    this.mrRunner = mrRunner;
    this.response = response;
    this.clock = clock;
  }

  @Override
  public void run() {
    // This mapreduce doesn't guarantee that foreign key relations are preserved, so isn't safe to
    // run on production. On other environments, data is fully wiped out occasionally anyway, so
    // having some broken data that isn't referred to isn't the end of the world.
    checkState(
        !RegistryEnvironment.get().equals(PRODUCTION),
        "This mapreduce is not safe to run on PRODUCTION.");

    if (tm().isOfy()) {
      mrRunner
          .setJobName("Delete load test data")
          .setModuleName("backend")
          .runMapOnly(
              new DeleteLoadTestDataMapper(isDryRun),
              ImmutableList.of(
                  createEntityInput(ContactResource.class), createEntityInput(HostResource.class)))
          .sendLinkToMapreduceConsole(response);
    } else {
      tm().transact(
              () -> {
                LOAD_TEST_REGISTRARS.forEach(this::deletePollMessages);
                tm().loadAllOfStream(ContactResource.class).forEach(this::deleteContact);
                tm().loadAllOfStream(HostResource.class).forEach(this::deleteHost);
              });
    }
  }

  private void deletePollMessages(String registrarId) {
    ImmutableList<PollMessage> pollMessages =
        PollFlowUtils.createPollMessageQuery(registrarId, END_OF_TIME).list();
    if (isDryRun) {
      logger.atInfo().log(
          "Would delete %d poll messages for registrar %s.", pollMessages.size(), registrarId);
    } else {
      pollMessages.forEach(tm()::delete);
    }
  }

  private void deleteContact(ContactResource contact) {
    if (!LOAD_TEST_REGISTRARS.contains(contact.getPersistedCurrentSponsorRegistrarId())) {
      return;
    }
    // We cannot remove contacts from domains in the general case, so we cannot delete contacts
    // that are linked to domains (since it would break the foreign keys)
    if (EppResourceUtils.isLinked(contact.createVKey(), clock.nowUtc())) {
      logger.atWarning().log(
          "Cannot delete contact with repo ID %s since it is referenced from a domain",
          contact.getRepoId());
      return;
    }
    deleteResource(contact);
  }

  private void deleteHost(HostResource host) {
    if (!LOAD_TEST_REGISTRARS.contains(host.getPersistedCurrentSponsorRegistrarId())) {
      return;
    }
    VKey<HostResource> hostVKey = host.createVKey();
    // We can remove hosts from linked domains, so we should do so then delete the hosts
    ImmutableSet<VKey<DomainBase>> linkedDomains =
        EppResourceUtils.getLinkedDomainKeys(hostVKey, clock.nowUtc(), null);
    tm().loadByKeys(linkedDomains)
        .values()
        .forEach(
            domain -> {
              ImmutableSet<VKey<HostResource>> remainingHosts =
                  domain.getNsHosts().stream()
                      .filter(vkey -> !vkey.equals(hostVKey))
                      .collect(toImmutableSet());
              tm().put(domain.asBuilder().setNameservers(remainingHosts).build());
            });
    deleteResource(host);
  }

  private void deleteResource(EppResource eppResource) {
    // In SQL, the only objects parented on the resource are poll messages (deleted above) and
    // history objects.
    ImmutableList<HistoryEntry> historyObjects =
        HistoryEntryDao.loadHistoryObjectsForResource(eppResource.createVKey());
    if (isDryRun) {
      logger.atInfo().log(
          "Would delete repo ID %s along with %d history objects",
          eppResource.getRepoId(), historyObjects.size());
    } else {
      historyObjects.forEach(tm()::delete);
      tm().delete(eppResource);
    }
  }

  /** Provides the map method that runs for each existing contact and host entity. */
  public static class DeleteLoadTestDataMapper extends Mapper<EppResource, Void, Void> {

    private static final long serialVersionUID = -3817710674062432694L;

    private final boolean isDryRun;

    public DeleteLoadTestDataMapper(boolean isDryRun) {
      this.isDryRun = isDryRun;
    }

    @Override
    public final void map(EppResource resource) {
      if (LOAD_TEST_REGISTRARS.contains(resource.getPersistedCurrentSponsorRegistrarId())) {
        deleteResource(resource);
        getContext()
            .incrementCounter(
                String.format("deleted %s entities", resource.getClass().getSimpleName()));
      } else {
        getContext().incrementCounter("skipped, not load test data");
      }
    }

    private void deleteResource(EppResource resource) {
      final Key<EppResourceIndex> eppIndex =
          Key.create(EppResourceIndex.create(Key.create(resource)));
      final Key<? extends ForeignKeyIndex<?>> fki = ForeignKeyIndex.createKey(resource);
      int numEntitiesDeleted =
          tm().transact(
                  () -> {
                    // This ancestor query selects all descendant entities.
                    List<Key<Object>> resourceAndDependentKeys =
                        auditedOfy().load().ancestor(resource).keys().list();
                    ImmutableSet<Key<?>> allKeys =
                        new ImmutableSet.Builder<Key<?>>()
                            .add(fki)
                            .add(eppIndex)
                            .addAll(resourceAndDependentKeys)
                            .build();
                    if (isDryRun) {
                      logger.atInfo().log("Would hard-delete the following entities: %s", allKeys);
                    } else {
                      auditedOfy().deleteWithoutBackup().keys(allKeys);
                    }
                    return allKeys.size();
                  });
      getContext().incrementCounter("total entities deleted", numEntitiesDeleted);
    }
  }
}
