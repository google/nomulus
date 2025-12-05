// Copyright 2025 The Nomulus Authors. All Rights Reserved.
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
import static com.google.common.net.MediaType.PLAIN_TEXT_UTF_8;
import static google.registry.flows.FlowUtils.marshalWithLenientRetry;
import static google.registry.model.common.FeatureFlag.FeatureName.MINIMUM_DATASET_CONTACTS_PROHIBITED;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.util.DateTimeUtils.END_OF_TIME;
import static google.registry.util.ResourceUtils.readResourceUtf8;
import static jakarta.servlet.http.HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
import static jakarta.servlet.http.HttpServletResponse.SC_NO_CONTENT;
import static jakarta.servlet.http.HttpServletResponse.SC_OK;
import static java.nio.charset.StandardCharsets.US_ASCII;

import com.google.common.base.Ascii;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.flogger.FluentLogger;
import com.google.common.util.concurrent.RateLimiter;
import google.registry.config.RegistryConfig.Config;
import google.registry.flows.EppController;
import google.registry.flows.EppRequestSource;
import google.registry.flows.PasswordOnlyTransportCredentials;
import google.registry.flows.StatelessRequestSessionMetadata;
import google.registry.model.common.FeatureFlag;
import google.registry.model.contact.Contact;
import google.registry.model.domain.DesignatedContact;
import google.registry.model.domain.Domain;
import google.registry.model.eppcommon.ProtocolDefinition;
import google.registry.model.eppoutput.EppOutput;
import google.registry.persistence.VKey;
import google.registry.request.Action;
import google.registry.request.Response;
import google.registry.request.auth.Auth;
import google.registry.request.lock.LockHandler;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.logging.Level;
import javax.annotation.Nullable;
import org.joda.time.Duration;

/**
 * An action that removes all contacts from all active (non-deleted) domains.
 *
 * <p>This implements part 1 of phase 3 of the Minimum Dataset migration, wherein we remove all uses
 * of contact objects in preparation for later removing all contact data from the system.
 *
 * <p>This runs as a singly threaded, resumable action that loads batches of domains still
 * containing contacts, and runs a superuser domain update on each one to remove the contacts,
 * leaving behind a record recording that update.
 */
@Action(
    service = Action.Service.BACKEND,
    path = RemoveAllDomainContactsAction.PATH,
    method = Action.Method.POST,
    auth = Auth.AUTH_ADMIN)
public class RemoveAllDomainContactsAction implements Runnable {

  public static final String PATH = "/_dr/task/removeAllDomainContacts";
  private static final String LOCK_NAME = "Remove all domain contacts";
  private static final String CONTACT_FMT = "<domain:contact type=\"%s\">%s</domain:contact>";

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();
  private final EppController eppController;
  private final String registryAdminClientId;
  private final LockHandler lockHandler;
  private final RateLimiter rateLimiter;
  private final Response response;
  private final String updateDomainXml;
  private int successes = 0;
  private int failures = 0;

  private static final int BATCH_SIZE = 10000;

  @Inject
  RemoveAllDomainContactsAction(
      EppController eppController,
      @Config("registryAdminClientId") String registryAdminClientId,
      LockHandler lockHandler,
      @Named("standardRateLimiter") RateLimiter rateLimiter,
      Response response) {
    this.eppController = eppController;
    this.registryAdminClientId = registryAdminClientId;
    this.lockHandler = lockHandler;
    this.rateLimiter = rateLimiter;
    this.response = response;
    this.updateDomainXml =
        readResourceUtf8(RemoveAllDomainContactsAction.class, "domain_remove_contacts.xml");
  }

  @Override
  public void run() {
    checkState(
        tm().transact(() -> FeatureFlag.isActiveNow(MINIMUM_DATASET_CONTACTS_PROHIBITED)),
        "Minimum dataset migration must be completed prior to running this action");
    response.setContentType(PLAIN_TEXT_UTF_8);

    Callable<Void> runner =
        () -> {
          try {
            runLocked();
            response.setStatus(SC_OK);
          } catch (Exception e) {
            logger.atSevere().withCause(e).log("Errored out during execution.");
            response.setStatus(SC_INTERNAL_SERVER_ERROR);
            response.setPayload(String.format("Errored out with cause: %s", e));
          }
          return null;
        };

    if (!lockHandler.executeWithLocks(runner, null, Duration.standardHours(1), LOCK_NAME)) {
      // Send a 200-series status code to prevent this conflicting action from retrying.
      response.setStatus(SC_NO_CONTENT);
      response.setPayload("Could not acquire lock; already running?");
    }
  }

  private void runLocked() {
    logger.atInfo().log("Removing contacts on all active domains.");

    List<String> domainRepoIdsBatch;
    do {
      domainRepoIdsBatch =
          tm().<List<String>>transact(
                  () ->
                      tm().getEntityManager()
                          .createQuery(
                              """
                              SELECT repoId FROM Domain WHERE deletionTime = :end_of_time AND NOT (
                                adminContact IS NULL AND billingContact IS NULL
                                AND registrantContact IS NULL AND techContact IS NULL)
                              """)
                          .setParameter("end_of_time", END_OF_TIME)
                          .setMaxResults(BATCH_SIZE)
                          .getResultList());

      for (String domainRepoId : domainRepoIdsBatch) {
        rateLimiter.acquire();
        runDomainUpdateFlow(domainRepoId);
      }
    } while (!domainRepoIdsBatch.isEmpty());
    String msg =
        String.format(
            "Finished; %d domains were successfully updated and %d errored out.",
            successes, failures);
    logger.at(failures == 0 ? Level.INFO : Level.WARNING).log(msg);
    response.setPayload(msg);
  }

  private void runDomainUpdateFlow(String repoId) {
    // Create a new transaction that the flow's execution will be enlisted in that loads the domain
    // transactionally. This way we can ensure that nothing else has modified the domain in question
    // in the intervening period since the query above found it. If a single domain update fails
    // permanently, log it and move on to not block processing all the other domains.
    try {
      boolean success = tm().transact(() -> runDomainUpdateFlowInner(repoId));
      if (success) {
        successes++;
      } else {
        failures++;
      }
    } catch (Throwable t) {
      logger.atWarning().withCause(t).log(
          "Failed updating domain with repoId %s; skipping.", repoId);
    }
  }

  /**
   * Runs the actual domain update flow and returns whether the contact removals were successful.
   */
  private boolean runDomainUpdateFlowInner(String repoId) {
    Domain domain = tm().loadByKey(VKey.create(Domain.class, repoId));
    if (!domain.getDeletionTime().equals(END_OF_TIME)) {
      // Domain has been deleted since the action began running; nothing further to be
      // done here.
      logger.atInfo().log("Nothing to process for deleted domain '%s'.", domain.getDomainName());
      return false;
    }
    logger.atInfo().log("Attempting to remove contacts on domain '%s'.", domain.getDomainName());

    StringBuilder sb = new StringBuilder();
    ImmutableMap<VKey<? extends Contact>, Contact> contacts =
        tm().loadByKeys(
                domain.getContacts().stream()
                    .map(DesignatedContact::getContactKey)
                    .collect(ImmutableSet.toImmutableSet()));

    // Collect all the (non-registrant) contacts referenced by the domain and compile an EPP XML
    // string that removes each one.
    for (DesignatedContact designatedContact : domain.getContacts()) {
      @Nullable Contact contact = contacts.get(designatedContact.getContactKey());
      if (contact == null) {
        logger.atWarning().log(
            "Domain '%s' referenced contact with repo ID '%s' that couldn't be" + " loaded.",
            domain.getDomainName(), designatedContact.getContactKey().getKey());
        continue;
      }
      sb.append(
              String.format(
                  CONTACT_FMT,
                  Ascii.toLowerCase(designatedContact.getType().name()),
                  contact.getContactId()))
          .append("\n");
    }

    String compiledXml =
        updateDomainXml
            .replace("%DOMAIN%", domain.getDomainName())
            .replace("%CONTACTS%", sb.toString());
    EppOutput output =
        eppController.handleEppCommand(
            new StatelessRequestSessionMetadata(
                registryAdminClientId, ProtocolDefinition.getVisibleServiceExtensionUris()),
            new PasswordOnlyTransportCredentials(),
            EppRequestSource.BACKEND,
            false,
            true,
            compiledXml.getBytes(US_ASCII));
    if (output.isSuccess()) {
      logger.atInfo().log(
          "Successfully removed contacts from domain '%s'.", domain.getDomainName());
    } else {
      logger.atWarning().log(
          "Failed removing contacts from domain '%s' with error %s.",
          domain.getDomainName(), new String(marshalWithLenientRetry(output), US_ASCII));
    }
    return output.isSuccess();
  }
}
