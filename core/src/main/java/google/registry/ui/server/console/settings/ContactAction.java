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
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static com.google.common.collect.Sets.difference;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.request.Action.Method.DELETE;
import static google.registry.request.Action.Method.GET;
import static google.registry.request.Action.Method.POST;
import static google.registry.request.Action.Method.PUT;
import static jakarta.servlet.http.HttpServletResponse.SC_OK;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Multimap;
import com.google.common.flogger.FluentLogger;
import google.registry.model.console.ConsolePermission;
import google.registry.model.console.ConsoleUpdateHistory;
import google.registry.model.console.User;
import google.registry.model.registrar.Registrar;
import google.registry.model.registrar.RegistrarPoc;
import google.registry.model.registrar.RegistrarPoc.Type;
import google.registry.request.Action;
import google.registry.request.Action.Service;
import google.registry.request.Parameter;
import google.registry.request.auth.Auth;
import google.registry.ui.server.console.ConsoleApiAction;
import google.registry.ui.server.console.ConsoleApiParams;
import jakarta.inject.Inject;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;

@Action(
    service = Service.CONSOLE,
    path = ContactAction.PATH,
    method = {GET, POST, DELETE, PUT},
    auth = Auth.AUTH_PUBLIC_LOGGED_IN)
public class ContactAction extends ConsoleApiAction {
  static final String PATH = "/console-api/settings/contacts";
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();
  private final Optional<RegistrarPoc> contact;
  private final String registrarId;

  @Inject
  public ContactAction(
      ConsoleApiParams consoleApiParams,
      @Parameter("registrarId") String registrarId,
      @Parameter("contact") Optional<RegistrarPoc> contact) {
    super(consoleApiParams);
    this.registrarId = registrarId;
    this.contact = contact;
  }

  @Override
  protected void getHandler(User user) {
    checkPermission(user, registrarId, ConsolePermission.VIEW_REGISTRAR_DETAILS);
    ImmutableList<RegistrarPoc> contacts =
        tm().transact(() -> RegistrarPoc.loadForRegistrar(registrarId));
    consoleApiParams.response().setStatus(SC_OK);
    consoleApiParams.response().setPayload(consoleApiParams.gson().toJson(contacts));
  }

  @Override
  protected void deleteHandler(User user) {
    updateContacts(
        user,
        "Deleted " + contact.get().getEmailAddress(),
        (registrar, oldContacts) ->
            oldContacts.stream()
                .filter(
                    oldContact ->
                        !oldContact.getEmailAddress().equals(contact.get().getEmailAddress()))
                .collect(toImmutableSet()));
  }

  @Override
  protected void postHandler(User user) {
    updateContacts(
        user,
        "Created " + contact.get().getEmailAddress(),
        (registrar, oldContacts) -> {
          RegistrarPoc newContact = contact.get();
          return ImmutableSet.<RegistrarPoc>builder()
              .addAll(oldContacts)
              .add(
                  new RegistrarPoc()
                      .asBuilder()
                      .setTypes(newContact.getTypes())
                      .setVisibleInRdapAsTech(newContact.getVisibleInRdapAsTech())
                      .setVisibleInRdapAsAdmin(newContact.getVisibleInRdapAsAdmin())
                      .setVisibleInDomainRdapAsAbuse(newContact.getVisibleInDomainRdapAsAbuse())
                      .setFaxNumber(newContact.getFaxNumber())
                      .setName(newContact.getName())
                      .setEmailAddress(newContact.getEmailAddress())
                      .setPhoneNumber(newContact.getPhoneNumber())
                      .setRegistrar(registrar)
                      .build())
              .build();
        });
  }

  @Override
  protected void putHandler(User user) {
    updateContacts(
        user,
        "Updated " + contact.get().getEmailAddress(),
        (registrar, oldContacts) -> {
          RegistrarPoc updatedContact = contact.get();
          return oldContacts.stream()
              .map(
                  oldContact ->
                      oldContact.getId().equals(updatedContact.getId())
                          ? oldContact
                              .asBuilder()
                              .setTypes(updatedContact.getTypes())
                              .setVisibleInRdapAsTech(updatedContact.getVisibleInRdapAsTech())
                              .setVisibleInRdapAsAdmin(updatedContact.getVisibleInRdapAsAdmin())
                              .setVisibleInDomainRdapAsAbuse(
                                  updatedContact.getVisibleInDomainRdapAsAbuse())
                              .setFaxNumber(updatedContact.getFaxNumber())
                              .setName(updatedContact.getName())
                              .setEmailAddress(updatedContact.getEmailAddress())
                              .setPhoneNumber(updatedContact.getPhoneNumber())
                              .build()
                          : oldContact)
              .collect(toImmutableSet());
        });
  }

  private void updateContacts(
      User user,
      String historyDescription,
      BiFunction<Registrar, ImmutableSet<RegistrarPoc>, ImmutableSet<RegistrarPoc>>
          contactsUpdater) {
    checkPermission(user, registrarId, ConsolePermission.EDIT_REGISTRAR_DETAILS);
    checkArgument(contact.isPresent(), "Contact parameter is not present");
    Registrar registrar =
        Registrar.loadByRegistrarId(registrarId)
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        String.format("Unknown registrar %s", registrarId)));

    ImmutableSet<RegistrarPoc> oldContacts = registrar.getContacts();
    ImmutableSet<RegistrarPoc> newContacts = contactsUpdater.apply(registrar, oldContacts);

    try {
      checkContactRequirements(oldContacts, newContacts);
    } catch (ContactRequirementException e) {
      logger.atWarning().withCause(e).log(
          "Error processing contacts post request for registrar: %s", registrarId);
      throw new IllegalArgumentException(e);
    }

    tm().transact(
            () -> {
              RegistrarPoc.updateContacts(registrar, newContacts);
              Registrar updatedRegistrar =
                  registrar.asBuilder().setContactsRequireSyncing(true).build();
              tm().put(updatedRegistrar);
              sendExternalUpdatesIfNecessary(
                  EmailInfo.create(registrar, updatedRegistrar, oldContacts, newContacts));
              finishAndPersistConsoleUpdateHistory(
                  new ConsoleUpdateHistory.Builder()
                      .setType(ConsoleUpdateHistory.Type.REGISTRAR_CONTACTS_UPDATE)
                      .setDescription(
                          String.format(
                              "%s%s%s",
                              registrarId,
                              ConsoleUpdateHistory.DESCRIPTION_SEPARATOR,
                              historyDescription)));
            });
    consoleApiParams.response().setStatus(SC_OK);
  }

  /**
   * Enforces business logic checks on registrar contacts.
   *
   * @throws ContactRequirementException if the checks fail.
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
    Optional<RegistrarPoc> domainRdapAbuseContact =
        getDomainRdapVisibleAbuseContact(updatedContacts);
    // If the new set has a domain RDAP abuse contact, it must have a phone number.
    if (domainRdapAbuseContact.isPresent()
        && domainRdapAbuseContact.get().getPhoneNumber() == null) {
      throw new ContactRequirementException(
          "The abuse contact visible in domain RDAP query must have a phone number");
    }
    // If there was a domain RDAP abuse contact in the old set, the new set must have one.
    if (getDomainRdapVisibleAbuseContact(existingContacts).isPresent()
        && domainRdapAbuseContact.isEmpty()) {
      throw new ContactRequirementException(
          "An abuse contact visible in domain RDAP query must be designated");
    }
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

  /**
   * Retrieves the registrar contact whose phone number and email address is visible in domain RDAP
   * query as abuse contact (if any).
   *
   * <p>Frontend processing ensures that only one contact can be set as abuse contact in domain RDAP
   * record.
   *
   * <p>Therefore, it is possible to return inside the loop once one such contact is found.
   */
  private static Optional<RegistrarPoc> getDomainRdapVisibleAbuseContact(
      Set<RegistrarPoc> contacts) {
    return contacts.stream().filter(RegistrarPoc::getVisibleInDomainRdapAsAbuse).findFirst();
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
  private static class ContactRequirementException extends RuntimeException {
    ContactRequirementException(String msg) {
      super(msg);
    }

    ContactRequirementException(Type type) {
      super(String.format("Must have at least one %s contact", type.getDisplayName()));
    }
  }
}
