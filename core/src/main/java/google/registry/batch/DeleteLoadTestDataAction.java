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
import static google.registry.persistence.PersistenceModule.TransactionIsolationLevel.TRANSACTION_REPEATABLE_READ;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.request.Action.Method.POST;
import static google.registry.request.RequestParameters.PARAM_DRY_RUN;
import static google.registry.util.DateTimeUtils.END_OF_TIME;
import static google.registry.util.RegistryEnvironment.PRODUCTION;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.flogger.FluentLogger;
import google.registry.flows.poll.PollFlowUtils;
import google.registry.model.EppResource;
import google.registry.model.EppResourceUtils;
import google.registry.model.contact.Contact;
import google.registry.model.domain.Domain;
import google.registry.model.host.Host;
import google.registry.model.poll.PollMessage;
import google.registry.model.reporting.HistoryEntry;
import google.registry.model.reporting.HistoryEntryDao;
import google.registry.persistence.VKey;
import google.registry.request.Action;
import google.registry.request.Parameter;
import google.registry.request.auth.Auth;
import google.registry.util.Clock;
import google.registry.util.RegistryEnvironment;
import jakarta.inject.Inject;

/**
 * Hard deletes load-test Contacts, Hosts, their subordinate history entries, and the associated
 * ForeignKey entities.
 *
 * <p>This only deletes contacts and hosts, NOT domains. To delete domains, use {@link
 * DeleteProberDataAction} and pass it the TLD(s) that the load test domains were created on. Note
 * that DeleteProberDataAction is safe enough to run in production whereas this action is not, but
 * this one does not need to be runnable in production because load testing isn't run against
 * production.
 */
@Action(
    service = Action.Service.BACKEND,
    path = "/_dr/task/deleteLoadTestData",
    method = POST,
    auth = Auth.AUTH_ADMIN)
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
  private final Clock clock;

  @Inject
  DeleteLoadTestDataAction(
      @Parameter(PARAM_DRY_RUN) boolean isDryRun,
      Clock clock) {
    this.isDryRun = isDryRun;
    this.clock = clock;
  }

  @Override
  public void run() {
    // This action doesn't guarantee that foreign key relations are preserved, so isn't safe to
    // run on production. On other environments, data is fully wiped out occasionally anyway, so
    // having some broken data that isn't referred to isn't the end of the world.
    checkState(
        !RegistryEnvironment.get().equals(PRODUCTION),
        "This action is not safe to run on PRODUCTION.");

    tm().transact(
            TRANSACTION_REPEATABLE_READ,
            () -> {
              LOAD_TEST_REGISTRARS.forEach(this::deletePollMessages);
              tm().loadAllOfStream(Contact.class).forEach(this::deleteContact);
              tm().loadAllOfStream(Host.class).forEach(this::deleteHost);
            });
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

  private void deleteContact(Contact contact) {
    if (!LOAD_TEST_REGISTRARS.contains(contact.getPersistedCurrentSponsorRegistrarId())) {
      return;
    }
    // We cannot remove contacts from domains in the general case, so we cannot delete contacts
    // that are linked to domains (since it would break the foreign keys)
    if (EppResourceUtils.isLinked(contact.createVKey(), clock.nowUtc())) {
      logger.atWarning().log(
          "Cannot delete contact with repo ID %s since it is referenced from a domain.",
          contact.getRepoId());
      return;
    }
    deleteResource(contact);
  }

  private void deleteHost(Host host) {
    if (!LOAD_TEST_REGISTRARS.contains(host.getPersistedCurrentSponsorRegistrarId())) {
      return;
    }
    VKey<Host> hostVKey = host.createVKey();
    // We can remove hosts from linked domains, so we should do so then delete the hosts
    ImmutableSet<VKey<Domain>> linkedDomains =
        EppResourceUtils.getLinkedDomainKeys(hostVKey, clock.nowUtc(), null);
    tm().loadByKeys(linkedDomains)
        .values()
        .forEach(
            domain -> {
              ImmutableSet<VKey<Host>> remainingHosts =
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
          "Would delete repo ID %s along with %d history objects.",
          eppResource.getRepoId(), historyObjects.size());
    } else {
      historyObjects.forEach(tm()::delete);
      tm().delete(eppResource);
    }
  }
}
