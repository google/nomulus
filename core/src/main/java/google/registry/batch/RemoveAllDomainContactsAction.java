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

package google.registry.batch;

import static com.google.common.net.MediaType.PLAIN_TEXT_UTF_8;
import static google.registry.flows.FlowUtils.marshalWithLenientRetry;
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
import google.registry.config.RegistryConfig.Config;
import google.registry.flows.EppController;
import google.registry.flows.EppRequestSource;
import google.registry.flows.PasswordOnlyTransportCredentials;
import google.registry.flows.StatelessRequestSessionMetadata;
import google.registry.model.contact.Contact;
import google.registry.model.domain.DesignatedContact;
import google.registry.model.domain.Domain;
import google.registry.model.eppcommon.ProtocolDefinition;
import google.registry.model.eppoutput.EppOutput;
import google.registry.persistence.VKey;
import google.registry.request.Action;
import google.registry.request.Action.GaeService;
import google.registry.request.Response;
import google.registry.request.auth.Auth;
import google.registry.request.lock.LockHandler;
import jakarta.inject.Inject;
import java.util.concurrent.Callable;
import java.util.logging.Level;
import java.util.stream.Stream;
import javax.annotation.Nullable;
import org.joda.time.Duration;

/**
 * An action that removes all contacts from all active (non-deleted) domains.
 *
 * <p>This implements part 1 of phase 3 of the Minimum Dataset migration, wherein we remove all uses
 * of contact objects in preparation for later removing all contact data from the system.
 */
@Action(
    service = GaeService.BACKEND,
    path = RemoveAllDomainContactsAction.PATH,
    auth = Auth.AUTH_ADMIN)
public class RemoveAllDomainContactsAction implements Runnable {

  public static final String PATH = "/_dr/task/removeAllDomainContacts";
  private static final String LOCK_NAME = "Remove all domain contacts";
  private static final String CONTACT_FMT = "<domain:contact type=\"%s\">%s</domain:contact>";

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();
  private final EppController eppController;
  private final String registryAdminClientId;
  private final LockHandler lockHandler;
  private final Response response;
  private final String updateDomainXml;
  private int successes = 0;
  private int failures = 0;

  @Inject
  RemoveAllDomainContactsAction(
      EppController eppController,
      @Config("registryAdminClientId") String registryAdminClientId,
      LockHandler lockHandler,
      Response response) {
    this.eppController = eppController;
    this.registryAdminClientId = registryAdminClientId;
    this.lockHandler = lockHandler;
    this.response = response;
    this.updateDomainXml =
        readResourceUtf8(RemoveAllDomainContactsAction.class, "domain_remove_contacts.xml");
  }

  @Override
  public void run() {
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

    Stream<String> domainRepoIdsToUpdate =
        tm().<Stream<String>>transact(
                () ->
                    tm().getEntityManager()
                        .createQuery(
                            """
                            SELECT repoId FROM Domain WHERE deletionTime = :end_of_time AND NOT (
                              admin_contact IS NULL AND billing_contact IS NULL
                              AND registrant_contact IS NULL AND tech_contact IS NULL)
                            """)
                        .setParameter("end_of_time", END_OF_TIME)
                        .getResultStream());

    domainRepoIdsToUpdate.forEach(this::runDomainUpdateFlow);
    String msg =
        String.format(
            "Finished; %d domains were successfully updated and %d errored out.",
            successes, failures);
    logger.at(failures == 0 ? Level.INFO : Level.WARNING).log(msg);
    response.setPayload(msg);
  }

  /**
   * Runs the actual domain update flow and returns whether the contact removals were successful.
   */
  private void runDomainUpdateFlow(String repoId) {
    // Create a new transaction that the flow's execution will be enlisted in that loads the domain
    // transactionally. This way we can ensure that nothing else has modified the domain in question
    // in the intervening period since the query above found it.
    boolean success =
        tm().transact(
                () -> {
                  Domain domain = tm().loadByKey(VKey.create(Domain.class, repoId));
                  if (!domain.getDeletionTime().equals(END_OF_TIME)) {
                    // Domain has been deleted since the action began running; nothing further to be
                    // done here.
                    logger.atInfo().log(
                        "Nothing to process for deleted domain '%s'.", domain.getDomainName());
                    return false;
                  }
                  logger.atInfo().log(
                      "Attempting to remove contacts on domain '%s'.", domain.getDomainName());

                  StringBuilder sb = new StringBuilder();
                  ImmutableMap<VKey<? extends Contact>, Contact> contacts =
                      tm().loadByKeys(
                              domain.getContacts().stream()
                                  .map(DesignatedContact::getContactKey)
                                  .collect(ImmutableSet.toImmutableSet()));
                  for (DesignatedContact designatedContact : domain.getContacts()) {
                    @Nullable Contact contact = contacts.get(designatedContact.getContactKey());
                    if (contact == null) {
                      logger.atWarning().log(
                          "Domain '%s' referenced contact with repo ID '%s' that couldn't be"
                              + " loaded.",
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

                  EppOutput output =
                      eppController.handleEppCommand(
                          new StatelessRequestSessionMetadata(
                              registryAdminClientId,
                              ProtocolDefinition.getVisibleServiceExtensionUris()),
                          new PasswordOnlyTransportCredentials(),
                          EppRequestSource.BACKEND,
                          false,
                          true,
                          updateDomainXml
                              .replace("%DOMAIN%", domain.getDomainName())
                              .replace("%CONTACTS%", sb.toString())
                              .getBytes(US_ASCII));
                  if (output.isSuccess()) {
                    logger.atInfo().log(
                        "Successfully removed contacts from domain '%s'.", domain.getDomainName());
                  } else {
                    logger.atWarning().log(
                        "Failed removing contacts from domain '%s' with error %s.",
                        domain.getDomainName(),
                        new String(marshalWithLenientRetry(output), US_ASCII));
                  }
                  return output.isSuccess();
                });

    if (success) {
      successes++;
    } else {
      failures++;
    }
  }
}
