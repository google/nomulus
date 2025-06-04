// Copyright 2023 The Nomulus Authors. All Rights Reserved.
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

package google.registry.ui.server.console.settings;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static com.google.common.collect.Sets.difference;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.request.Action.Method.GET;
import static google.registry.request.Action.Method.POST;
import static jakarta.servlet.http.HttpServletResponse.SC_OK;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Multimap;
import com.google.common.flogger.FluentLogger;
import google.registry.model.console.ConsolePermission;
import google.registry.model.console.User;
import google.registry.model.registrar.Registrar;
import google.registry.model.registrar.RegistrarPoc;
import google.registry.model.registrar.RegistrarPoc.Type;
import google.registry.request.Action;
import google.registry.request.Action.GaeService;
import google.registry.request.Action.GkeService;
import google.registry.request.Parameter;
import google.registry.request.auth.Auth;
import google.registry.ui.forms.FormException;
import google.registry.ui.server.RegistrarFormFields;
import google.registry.ui.server.console.ConsoleApiAction;
import google.registry.ui.server.console.ConsoleApiParams;
import jakarta.inject.Inject;
import java.util.Collections;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

@Action(
    service = GaeService.DEFAULT,
    gkeService = GkeService.CONSOLE,
    path = ContactAction.PATH,
    method = {GET, POST},
    auth = Auth.AUTH_PUBLIC_LOGGED_IN)
public class ContactAction extends ConsoleApiAction {
  static final String PATH = "/console-api/settings/contacts";
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();
  private final Optional<ImmutableSet<RegistrarPoc>> contacts;
  private final String registrarId;

  @Inject
  public ContactAction(
      ConsoleApiParams consoleApiParams,
      @Parameter("registrarId") String registrarId,
      @Parameter("contacts") Optional<ImmutableSet<RegistrarPoc>> contacts) {
    super(consoleApiParams);
    this.registrarId = registrarId;
    this.contacts = contacts;
  }

  @Override
  protected void getHandler(User user) {
    checkPermission(user, registrarId, ConsolePermission.VIEW_REGISTRAR_DETAILS);
    ImmutableList<RegistrarPoc> allContacts =
        tm().transact(
                () ->
                    RegistrarPoc.loadForRegistrar(registrarId).stream()
                        .filter(r -> !r.getTypes().isEmpty())
                        .collect(toImmutableList()));

    consoleApiParams.response().setStatus(SC_OK);
    consoleApiParams.response().setPayload(consoleApiParams.gson().toJson(allContacts));
  }

  @Override
  protected void postHandler(User user) {
    checkPermission(user, registrarId, ConsolePermission.EDIT_REGISTRAR_DETAILS);
    checkArgument(contacts.isPresent(), "Contacts parameter is not present");
    Registrar registrar =
        Registrar.loadByRegistrarId(registrarId)
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        String.format("Unknown registrar %s", registrarId)));

    ImmutableSet<RegistrarPoc> oldContacts = registrar.getContacts();
    ImmutableSet<RegistrarPoc> updatedContacts =
        RegistrarFormFields.getRegistrarContactBuilders(
                oldContacts,
                Collections.singletonMap(
                    "contacts",
                    contacts.get().stream()
                        .map(RegistrarPoc::toJsonMap)
                        .collect(toImmutableList())))
            .stream()
            .map(builder -> builder.setRegistrar(registrar).build())
            .collect(toImmutableSet());

    try {
      checkContactRequirements(oldContacts, updatedContacts);
    } catch (FormException e) {
      logger.atWarning().withCause(e).log(
          "Error processing contacts post request for registrar: %s", registrarId);
      throw new IllegalArgumentException(e);
    }

    tm().transact(
            () -> {
              RegistrarPoc.updateContacts(registrar, updatedContacts);
              Registrar updatedRegistrar =
                  registrar.asBuilder().setContactsRequireSyncing(true).build();
              tm().put(updatedRegistrar);
              sendExternalUpdatesIfNecessary(
                  EmailInfo.create(registrar, updatedRegistrar, oldContacts, updatedContacts));
            });

    consoleApiParams.response().setStatus(SC_OK);
  }

  /**
   * Enforces business logic checks on registrar contacts.
   *
   * @throws FormException if the checks fail.
   */
  private static void checkContactRequirements(
      ImmutableSet<RegistrarPoc> existingContacts, ImmutableSet<RegistrarPoc> updatedContacts) {
    // Check that no two contacts use the same email address.
    Set<String> emails = new HashSet<>();
    for (RegistrarPoc contact : updatedContacts) {
      if (!emails.add(contact.getEmailAddress())) {
        throw new ContactRequirementException(
            String.format(
                "One email address (%s) cannot be used for multiple contacts",
                contact.getEmailAddress()));
      }
    }
    // Check that required contacts don't go away, once they are set.
    Multimap<Type, RegistrarPoc> oldContactsByType = HashMultimap.create();
    for (RegistrarPoc contact : existingContacts) {
      for (Type t : contact.getTypes()) {
        oldContactsByType.put(t, contact);
      }
    }
    Multimap<Type, RegistrarPoc> newContactsByType = HashMultimap.create();
    for (RegistrarPoc contact : updatedContacts) {
      for (Type t : contact.getTypes()) {
        newContactsByType.put(t, contact);
      }
    }
    for (Type t : difference(oldContactsByType.keySet(), newContactsByType.keySet())) {
      if (t.isRequired()) {
        throw new ContactRequirementException(t);
      }
    }
    enforcePrimaryContactRestrictions(oldContactsByType, newContactsByType);
    ensurePhoneNumberNotRemovedForContactTypes(oldContactsByType, newContactsByType, Type.TECH);
    Optional<RegistrarPoc> domainWhoisAbuseContact =
        getDomainWhoisVisibleAbuseContact(updatedContacts);
    // If the new set has a domain WHOIS abuse contact, it must have a phone number.
    if (domainWhoisAbuseContact.isPresent()
        && domainWhoisAbuseContact.get().getPhoneNumber() == null) {
      throw new ContactRequirementException(
          "The abuse contact visible in domain WHOIS query must have a phone number");
    }
    // If there was a domain WHOIS abuse contact in the old set, the new set must have one.
    if (getDomainWhoisVisibleAbuseContact(existingContacts).isPresent()
        && domainWhoisAbuseContact.isEmpty()) {
      throw new ContactRequirementException(
          "An abuse contact visible in domain WHOIS query must be designated");
    }
    checkContactRegistryLockRequirements(existingContacts, updatedContacts);
  }

  private static void enforcePrimaryContactRestrictions(
      Multimap<Type, RegistrarPoc> oldContactsByType,
      Multimap<Type, RegistrarPoc> newContactsByType) {
    ImmutableSet<String> oldAdminEmails =
        oldContactsByType.get(Type.ADMIN).stream()
            .map(RegistrarPoc::getEmailAddress)
            .collect(toImmutableSet());
    ImmutableSet<String> newAdminEmails =
        newContactsByType.get(Type.ADMIN).stream()
            .map(RegistrarPoc::getEmailAddress)
            .collect(toImmutableSet());
    if (!newAdminEmails.containsAll(oldAdminEmails)) {
      throw new ContactRequirementException(
          "Cannot remove or change the email address of primary contacts");
    }
  }

  private static void checkContactRegistryLockRequirements(
      ImmutableSet<RegistrarPoc> existingContacts, ImmutableSet<RegistrarPoc> updatedContacts) {
    // Any contact(s) with new passwords must be allowed to set them
    for (RegistrarPoc updatedContact : updatedContacts) {
      if (updatedContact.isRegistryLockAllowed()
          || updatedContact.isAllowedToSetRegistryLockPassword()) {
        RegistrarPoc existingContact =
            existingContacts.stream()
                .filter(
                    contact -> contact.getEmailAddress().equals(updatedContact.getEmailAddress()))
                .findFirst()
                .orElseThrow(
                    () ->
                        new FormException(
                            "Cannot set registry lock password directly on new contact"));
        // Can't modify registry lock email address
        if (!Objects.equals(
            updatedContact.getRegistryLockEmailAddress(),
            existingContact.getRegistryLockEmailAddress())) {
          throw new FormException("Cannot modify registryLockEmailAddress through the UI");
        }
        if (updatedContact.isRegistryLockAllowed()) {
          // the password must have been set before or the user was allowed to set it now
          if (!existingContact.isAllowedToSetRegistryLockPassword()
              && !existingContact.isRegistryLockAllowed()) {
            throw new FormException("Registrar contact not allowed to set registry lock password");
          }
        }
        if (updatedContact.isAllowedToSetRegistryLockPassword()) {
          if (!existingContact.isAllowedToSetRegistryLockPassword()) {
            throw new FormException(
                "Cannot modify isAllowedToSetRegistryLockPassword through the UI");
          }
        }
      }
    }

    // Any previously-existing contacts with registry lock enabled cannot be deleted
    existingContacts.stream()
        .filter(RegistrarPoc::isRegistryLockAllowed)
        .forEach(
            contact -> {
              Optional<RegistrarPoc> updatedContactOptional =
                  updatedContacts.stream()
                      .filter(
                          updatedContact ->
                              updatedContact.getEmailAddress().equals(contact.getEmailAddress()))
                      .findFirst();
              if (updatedContactOptional.isEmpty()) {
                throw new FormException(
                    String.format(
                        "Cannot delete the contact %s that has registry lock enabled",
                        contact.getEmailAddress()));
              }
              if (!updatedContactOptional.get().isRegistryLockAllowed()) {
                throw new FormException(
                    String.format(
                        "Cannot remove the ability to use registry lock on the contact %s",
                        contact.getEmailAddress()));
              }
            });
  }

  /**
   * Retrieves the registrar contact whose phone number and email address is visible in domain WHOIS
   * query as abuse contact (if any).
   *
   * <p>Frontend processing ensures that only one contact can be set as abuse contact in domain
   * WHOIS record.
   *
   * <p>Therefore, it is possible to return inside the loop once one such contact is found.
   */
  private static Optional<RegistrarPoc> getDomainWhoisVisibleAbuseContact(
      Set<RegistrarPoc> contacts) {
    return contacts.stream().filter(RegistrarPoc::getVisibleInDomainWhoisAsAbuse).findFirst();
  }

  /**
   * Ensure that for each given registrar type, a phone number is present after update, if there was
   * one before.
   */
  private static void ensurePhoneNumberNotRemovedForContactTypes(
      Multimap<Type, RegistrarPoc> oldContactsByType,
      Multimap<Type, RegistrarPoc> newContactsByType,
      Type... types) {
    for (Type type : types) {
      if (oldContactsByType.get(type).stream().anyMatch(contact -> contact.getPhoneNumber() != null)
          && newContactsByType.get(type).stream()
              .noneMatch(contact -> contact.getPhoneNumber() != null)) {
        throw new ContactRequirementException(
            String.format(
                "Please provide a phone number for at least one %s contact",
                type.getDisplayName()));
      }
    }
  }

  /** Thrown when a set of contacts doesn't meet certain constraints. */
  private static class ContactRequirementException extends FormException {
    ContactRequirementException(String msg) {
      super(msg);
    }

    ContactRequirementException(Type type) {
      super(String.format("Must have at least one %s contact", type.getDisplayName()));
    }
  }
}
