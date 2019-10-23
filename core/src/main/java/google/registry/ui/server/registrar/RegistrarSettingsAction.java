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

package google.registry.ui.server.registrar;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static com.google.common.collect.Sets.difference;
import static google.registry.config.RegistryEnvironment.PRODUCTION;
import static google.registry.export.sheet.SyncRegistrarsSheetAction.enqueueRegistrarSheetSync;
import static google.registry.model.ofy.ObjectifyService.ofy;
import static google.registry.model.transaction.TransactionManagerFactory.tm;
import static google.registry.security.JsonResponseHelper.Status.ERROR;
import static google.registry.security.JsonResponseHelper.Status.SUCCESS;
import static google.registry.util.PreconditionsUtils.checkArgumentPresent;

import com.google.auto.value.AutoValue;
import com.google.common.base.Ascii;
import com.google.common.base.Strings;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Multimap;
import com.google.common.collect.Sets;
import com.google.common.collect.Streams;
import com.google.common.flogger.FluentLogger;
import google.registry.config.RegistryEnvironment;
import google.registry.model.registrar.Registrar;
import google.registry.model.registrar.RegistrarContact;
import google.registry.model.registrar.RegistrarContact.Type;
import google.registry.request.Action;
import google.registry.request.HttpException.BadRequestException;
import google.registry.request.HttpException.ForbiddenException;
import google.registry.request.JsonActionRunner;
import google.registry.request.auth.Auth;
import google.registry.request.auth.AuthResult;
import google.registry.request.auth.AuthenticatedRegistrarAccessor;
import google.registry.request.auth.AuthenticatedRegistrarAccessor.RegistrarAccessDeniedException;
import google.registry.request.auth.AuthenticatedRegistrarAccessor.Role;
import google.registry.security.JsonResponseHelper;
import google.registry.ui.forms.FormException;
import google.registry.ui.forms.FormFieldException;
import google.registry.ui.server.RegistrarFormFields;
import google.registry.ui.server.SendEmailUtils;
import google.registry.util.AppEngineServiceUtils;
import google.registry.util.CollectionUtils;
import google.registry.util.DiffUtils;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import javax.inject.Inject;
import org.joda.time.DateTime;

/**
 * Admin servlet that allows creating or updating a registrar. Deletes are not allowed so as to
 * preserve history.
 */
@Action(
    service = Action.Service.DEFAULT,
    path = RegistrarSettingsAction.PATH,
    method = Action.Method.POST,
    auth = Auth.AUTH_PUBLIC_LOGGED_IN)
public class RegistrarSettingsAction implements Runnable, JsonActionRunner.JsonAction {

  public static final String PATH = "/registrar-settings";

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  static final String OP_PARAM = "op";
  static final String ARGS_PARAM = "args";
  static final String ID_PARAM = "id";

  @Inject JsonActionRunner jsonActionRunner;
  @Inject AppEngineServiceUtils appEngineServiceUtils;
  @Inject RegistrarConsoleMetrics registrarConsoleMetrics;
  @Inject SendEmailUtils sendEmailUtils;
  @Inject AuthenticatedRegistrarAccessor registrarAccessor;
  @Inject AuthResult authResult;

  @Inject RegistrarSettingsAction() {}

  private static final Predicate<RegistrarContact> HAS_PHONE =
      contact -> contact.getPhoneNumber() != null;

  @Override
  public void run() {
    jsonActionRunner.run(this);
  }

  @Override
  public Map<String, Object> handleJsonRequest(Map<String, ?> input) {
    if (input == null) {
      throw new BadRequestException("Malformed JSON");
    }

    String clientId = (String) input.get(ID_PARAM);
    if (Strings.isNullOrEmpty(clientId)) {
      throw new BadRequestException(String.format("Missing key for resource ID: %s", ID_PARAM));
    }

    // Process the operation.  Though originally derived from a CRUD
    // handler, registrar-settings really only supports read and update.
    String op = Optional.ofNullable((String) input.get(OP_PARAM)).orElse("read");
    @SuppressWarnings("unchecked")
    Map<String, ?> args = (Map<String, Object>)
        Optional.<Object>ofNullable(input.get(ARGS_PARAM)).orElse(ImmutableMap.of());

    logger.atInfo().log("Received request '%s' on registrar '%s' with args %s", op, clientId, args);
    String status = "SUCCESS";
    try {
      switch (op) {
        case "update":
          return update(args, clientId).toJsonResponse();
        case "read":
          return read(clientId).toJsonResponse();
        default:
          throw new IllegalArgumentException("Unknown or unsupported operation: " + op);
      }
    } catch (Throwable e) {
      logger.atWarning().withCause(e).log(
          "Failed to perform operation '%s' on registrar '%s' for args %s", op, clientId, args);
      status = "ERROR: " + e.getClass().getSimpleName();
      if (e instanceof FormFieldException) {
        FormFieldException formFieldException = (FormFieldException) e;
        return JsonResponseHelper.createFormFieldError(
            formFieldException.getMessage(), formFieldException.getFieldName());
      }
      return JsonResponseHelper.create(
          ERROR, Optional.ofNullable(e.getMessage()).orElse("Unspecified error"));
    } finally {
      registrarConsoleMetrics.registerSettingsRequest(
          clientId, op, registrarAccessor.getRolesForRegistrar(clientId), status);
    }
  }

  @AutoValue
  abstract static class RegistrarResult {
    abstract String message();

    abstract Registrar registrar();

    Map<String, Object> toJsonResponse() {
      return JsonResponseHelper.create(SUCCESS, message(), registrar().toJsonMap());
    }

    static RegistrarResult create(String message, Registrar registrar) {
      return new AutoValue_RegistrarSettingsAction_RegistrarResult(message, registrar);
    }
  }

  private RegistrarResult read(String clientId) {
    try {
      return RegistrarResult.create("Success", registrarAccessor.getRegistrar(clientId));
    } catch (RegistrarAccessDeniedException e) {
      throw new ForbiddenException(e.getMessage(), e);
    }
  }

  private RegistrarResult update(final Map<String, ?> args, String clientId) {
    return tm()
        .transact(
            () -> {
              // We load the registrar here rather than outside of the transaction - to make
              // sure we have the latest version. This one is loaded inside the transaction, so it's
              // guaranteed to not change before we update it.
              Registrar registrar;
              try {
                registrar = registrarAccessor.getRegistrar(clientId);
              } catch (RegistrarAccessDeniedException e) {
                throw new ForbiddenException(e.getMessage(), e);
              }
              // Verify that the registrar hasn't been changed.
              // To do that - we find the latest update time (or null if the registrar has been
              // deleted) and compare to the update time from the args. The update time in the args
              // comes from the read that gave the UI the data - if it's out of date, then the UI
              // had out of date data.
              DateTime latest = registrar.getLastUpdateTime();
              DateTime latestFromArgs =
                  RegistrarFormFields.LAST_UPDATE_TIME.extractUntyped(args).get();
              if (!latestFromArgs.equals(latest)) {
                logger.atWarning().log(
                    "registrar changed since reading the data! "
                        + " Last updated at %s, but args data last updated at %s",
                    latest, latestFromArgs);
                throw new IllegalStateException(
                    "registrar has been changed by someone else. Please reload and retry.");
              }

              // Keep the current contacts so we can later check that no required contact was
              // removed, email the changes to the contacts
              ImmutableSet<RegistrarContact> contacts = registrar.getContacts();

              Registrar updatedRegistrar = registrar;
              // Do OWNER only updates to the registrar from the request.
              updatedRegistrar = checkAndUpdateOwnerControlledFields(updatedRegistrar, args);
              // Do ADMIN only updates to the registrar from the request.
              updatedRegistrar = checkAndUpdateAdminControlledFields(updatedRegistrar, args);

              // read the contacts from the request.
              ImmutableSet<RegistrarContact> updatedContacts = readContacts(registrar, args);

              // Save the updated contacts
              if (!updatedContacts.equals(contacts)) {
                if (!registrarAccessor.hasRoleOnRegistrar(Role.OWNER, registrar.getClientId())) {
                  throw new ForbiddenException("Only OWNERs can update the contacts");
                }
                checkContactRequirements(contacts, updatedContacts);
                RegistrarContact.updateContacts(updatedRegistrar, updatedContacts);
                updatedRegistrar =
                    updatedRegistrar.asBuilder().setContactsRequireSyncing(true).build();
              }

              // Save the updated registrar
              if (!updatedRegistrar.equals(registrar)) {
                ofy().save().entity(updatedRegistrar);
              }

              // Email and return update.
              sendExternalUpdatesIfNecessary(
                  registrar, contacts, updatedRegistrar, updatedContacts);
              return RegistrarResult.create("Saved " + clientId, updatedRegistrar);
            });
  }

  private Map<String, Object> expandRegistrarWithContacts(
      Iterable<RegistrarContact> contacts, Registrar registrar) {
    ImmutableSet<Map<String, Object>> expandedContacts =
        Streams.stream(contacts)
            .map(RegistrarContact::toDiffableFieldMap)
            // Note: per the javadoc, toDiffableFieldMap includes sensitive data but we don't want
            // to display it here
            .peek(
                map -> {
                  map.remove("registryLockPasswordHash");
                  map.remove("registryLockPasswordSalt");
                })
            .collect(toImmutableSet());
    // Use LinkedHashMap here to preserve ordering; null values mean we can't use ImmutableMap.
    LinkedHashMap<String, Object> result = new LinkedHashMap<>(registrar.toDiffableFieldMap());
    result.put("contacts", expandedContacts);
    return result;
  }

  /**
   * Updates registrar with the OWNER-controlled args from the http request.
   *
   * <p>If any changes were made and the user isn't an OWNER - throws a {@link ForbiddenException}.
   */
  private Registrar checkAndUpdateOwnerControlledFields(
      Registrar initialRegistrar, Map<String, ?> args) {

    Registrar.Builder builder = initialRegistrar.asBuilder();

    // WHOIS
    //
    // Because of how whoisServer handles "default value", it's possible that setting the existing
    // value will still change the Registrar. So we first check whether the value has changed.
    //
    // The problem is - if the Registrar has a "null" whoisServer value, the console gets the
    // "default value" instead of the actual (null) value.
    // This was done so we display the "default" value, but it also means that it always looks like
    // the user updated the whoisServer value from "null" to the default value.
    //
    // TODO(b/119913848):once a null whoisServer value is sent to the console as "null", there's no
    // need to check for equality before setting the value in the builder.
    String updatedWhoisServer =
        RegistrarFormFields.WHOIS_SERVER_FIELD.extractUntyped(args).orElse(null);
    if (!Objects.equals(initialRegistrar.getWhoisServer(), updatedWhoisServer)) {
      builder.setWhoisServer(updatedWhoisServer);
    }
    builder.setUrl(RegistrarFormFields.URL_FIELD.extractUntyped(args).orElse(null));

    // If the email is already null / empty - we can keep it so. But if it's set - it's required to
    // remain set.
    (Strings.isNullOrEmpty(initialRegistrar.getEmailAddress())
            ? RegistrarFormFields.EMAIL_ADDRESS_FIELD_OPTIONAL
            : RegistrarFormFields.EMAIL_ADDRESS_FIELD_REQUIRED)
        .extractUntyped(args)
        .ifPresent(builder::setEmailAddress);
    builder.setPhoneNumber(
        RegistrarFormFields.PHONE_NUMBER_FIELD.extractUntyped(args).orElse(null));
    builder.setFaxNumber(
        RegistrarFormFields.FAX_NUMBER_FIELD.extractUntyped(args).orElse(null));
    builder.setLocalizedAddress(
        RegistrarFormFields.L10N_ADDRESS_FIELD.extractUntyped(args).orElse(null));

    // Security
    builder.setIpAddressWhitelist(
        RegistrarFormFields.IP_ADDRESS_WHITELIST_FIELD
            .extractUntyped(args)
            .orElse(ImmutableList.of()));
    RegistrarFormFields.CLIENT_CERTIFICATE_FIELD
        .extractUntyped(args)
        .ifPresent(
            certificate -> builder.setClientCertificate(certificate, tm().getTransactionTime()));
    RegistrarFormFields.FAILOVER_CLIENT_CERTIFICATE_FIELD
        .extractUntyped(args)
        .ifPresent(
            certificate ->
                builder.setFailoverClientCertificate(certificate, tm().getTransactionTime()));

    return checkNotChangedUnlessAllowed(builder, initialRegistrar, Role.OWNER);
  }

  /**
   * Updates a registrar with the ADMIN-controlled args from the http request.
   *
   * <p>If any changes were made and the user isn't an ADMIN - throws a {@link ForbiddenException}.
   */
  private Registrar checkAndUpdateAdminControlledFields(
      Registrar initialRegistrar, Map<String, ?> args) {
    Registrar.Builder builder = initialRegistrar.asBuilder();

    Set<String> updatedAllowedTlds =
        RegistrarFormFields.ALLOWED_TLDS_FIELD.extractUntyped(args).orElse(ImmutableSet.of());
    // Temporarily block anyone from removing an allowed TLD.
    // This is so we can start having Support users use the console in production before we finish
    // implementing configurable access control.
    // TODO(b/119549884): remove this code once configurable access control is implemented.
    if (!Sets.difference(initialRegistrar.getAllowedTlds(), updatedAllowedTlds).isEmpty()) {
      throw new ForbiddenException("Can't remove allowed TLDs using the console.");
    }
    if (!Sets.difference(updatedAllowedTlds, initialRegistrar.getAllowedTlds()).isEmpty()) {
      // If a REAL registrar isn't in compliance with regards to having an abuse contact set,
      // prevent addition of allowed TLDs until that's fixed.
      if (Registrar.Type.REAL.equals(initialRegistrar.getType())
          && PRODUCTION.equals(RegistryEnvironment.get())) {
        checkArgumentPresent(
            initialRegistrar.getWhoisAbuseContact(),
            "Cannot add allowed TLDs if there is no WHOIS abuse contact set.");
      }
    }
    builder.setAllowedTlds(updatedAllowedTlds);
    return checkNotChangedUnlessAllowed(builder, initialRegistrar, Role.ADMIN);
  }

  /**
   * Makes sure builder.build is different than originalRegistrar only if we have the correct role.
   *
   * <p>On success, returns {@code builder.build()}.
   */
  private Registrar checkNotChangedUnlessAllowed(
      Registrar.Builder builder,
      Registrar originalRegistrar,
      Role allowedRole) {
    Registrar updatedRegistrar =  builder.build();
    if (updatedRegistrar.equals(originalRegistrar)) {
      return updatedRegistrar;
    }
    checkArgument(
        updatedRegistrar.getClientId().equals(originalRegistrar.getClientId()),
        "Can't change clientId (%s -> %s)",
        originalRegistrar.getClientId(),
        updatedRegistrar.getClientId());
    if (registrarAccessor.hasRoleOnRegistrar(allowedRole, originalRegistrar.getClientId())) {
      return updatedRegistrar;
    }
    Map<?, ?> diffs =
        DiffUtils.deepDiff(
            originalRegistrar.toDiffableFieldMap(),
            updatedRegistrar.toDiffableFieldMap(),
            true);
    throw new ForbiddenException(
        String.format("Unauthorized: only %s can change fields %s", allowedRole, diffs.keySet()));
  }

  /** Reads the contacts from the supplied args. */
  public static ImmutableSet<RegistrarContact> readContacts(
      Registrar registrar, Map<String, ?> args) {

    ImmutableSet.Builder<RegistrarContact> contacts = new ImmutableSet.Builder<>();
    Optional<List<RegistrarContact.Builder>> builders =
        RegistrarFormFields.CONTACTS_FIELD.extractUntyped(args);
    if (builders.isPresent()) {
      builders.get().forEach(c -> contacts.add(c.setParent(registrar).build()));
    }

    return contacts.build();
  }

  /**
   * Enforces business logic checks on registrar contacts.
   *
   * @throws FormException if the checks fail.
   */
  void checkContactRequirements(
      Set<RegistrarContact> existingContacts, Set<RegistrarContact> updatedContacts) {
    // Check that no two contacts use the same email address.
    Set<String> emails = new HashSet<>();
    for (RegistrarContact contact : updatedContacts) {
      if (!emails.add(contact.getEmailAddress())) {
        throw new ContactRequirementException(String.format(
            "One email address (%s) cannot be used for multiple contacts",
            contact.getEmailAddress()));
      }
    }
    // Check that required contacts don't go away, once they are set.
    Multimap<Type, RegistrarContact> oldContactsByType = HashMultimap.create();
    for (RegistrarContact contact : existingContacts) {
      for (Type t : contact.getTypes()) {
        oldContactsByType.put(t, contact);
      }
    }
    Multimap<Type, RegistrarContact> newContactsByType = HashMultimap.create();
    for (RegistrarContact contact : updatedContacts) {
      for (Type t : contact.getTypes()) {
        newContactsByType.put(t, contact);
      }
    }
    for (Type t : difference(oldContactsByType.keySet(), newContactsByType.keySet())) {
      if (t.isRequired()) {
        throw new ContactRequirementException(t);
      }
    }
    ensurePhoneNumberNotRemovedForContactTypes(oldContactsByType, newContactsByType, Type.TECH);
    Optional<RegistrarContact> domainWhoisAbuseContact =
        getDomainWhoisVisibleAbuseContact(updatedContacts);
    // If the new set has a domain WHOIS abuse contact, it must have a phone number.
    if (domainWhoisAbuseContact.isPresent()
        && domainWhoisAbuseContact.get().getPhoneNumber() == null) {
      throw new ContactRequirementException(
          "The abuse contact visible in domain WHOIS query must have a phone number");
    }
    // If there was a domain WHOIS abuse contact in the old set, the new set must have one.
    if (getDomainWhoisVisibleAbuseContact(existingContacts).isPresent()
        && !domainWhoisAbuseContact.isPresent()) {
      throw new ContactRequirementException(
          "An abuse contact visible in domain WHOIS query must be designated");
    }

    // Any contact(s) with new passwords must be allowed to set them
    for (RegistrarContact updatedContact : updatedContacts) {
      if (updatedContact.isRegistryLockAllowed()
          || updatedContact.isAllowedToSetRegistryLockPassword()) {
        RegistrarContact existingContact =
            existingContacts.stream()
                .filter(
                    contact -> contact.getEmailAddress().equals(updatedContact.getEmailAddress()))
                .findFirst()
                .orElseThrow(
                    () ->
                        new FormException(
                            "Not allowed to set registry lock password directly on new contact"));
        if (!existingContact.isAllowedToSetRegistryLockPassword()) {
          throw new FormException("Registrar contact not allowed to set registry lock password");
        }
      }
    }
  }

  /**
   * Ensure that for each given registrar type, a phone number is present after update, if there was
   * one before.
   */
  private static void ensurePhoneNumberNotRemovedForContactTypes(
      Multimap<Type, RegistrarContact> oldContactsByType,
      Multimap<Type, RegistrarContact> newContactsByType,
      Type... types) {
    for (Type type : types) {
      if (oldContactsByType.get(type).stream().anyMatch(HAS_PHONE)
          && newContactsByType.get(type).stream().noneMatch(HAS_PHONE)) {
        throw new ContactRequirementException(
            String.format(
                "Please provide a phone number for at least one %s contact",
                type.getDisplayName()));
      }
    }
  }

  /**
   * Retrieves the registrar contact whose phone number and email address is visible in domain WHOIS
   * query as abuse contact (if any).
   *
   * <p>Frontend processing ensures that only one contact can be set as abuse contact in domain
   * WHOIS record. Therefore it is possible to return inside the loop once one such contact is
   * found.
   */
  private static Optional<RegistrarContact> getDomainWhoisVisibleAbuseContact(
      Set<RegistrarContact> contacts) {
    return contacts.stream().filter(RegistrarContact::getVisibleInDomainWhoisAsAbuse).findFirst();
  }

  /**
   * Determines if any changes were made to the registrar besides the lastUpdateTime, and if so,
   * sends an email with a diff of the changes to the configured notification email address and all
   * contact addresses and enqueues a task to re-sync the registrar sheet.
   */
  private void sendExternalUpdatesIfNecessary(
      Registrar existingRegistrar,
      ImmutableSet<RegistrarContact> existingContacts,
      Registrar updatedRegistrar,
      ImmutableSet<RegistrarContact> updatedContacts) {
    if (!sendEmailUtils.hasRecipients() && existingContacts.isEmpty()) {
      return;
    }

    Map<?, ?> diffs =
        DiffUtils.deepDiff(
            expandRegistrarWithContacts(existingContacts, existingRegistrar),
            expandRegistrarWithContacts(updatedContacts, updatedRegistrar),
            true);
    @SuppressWarnings("unchecked")
    Set<String> changedKeys = (Set<String>) diffs.keySet();
    if (CollectionUtils.difference(changedKeys, "lastUpdateTime").isEmpty()) {
      return;
    }
    enqueueRegistrarSheetSync(appEngineServiceUtils.getCurrentVersionHostname("backend"));
    String environment = Ascii.toLowerCase(String.valueOf(RegistryEnvironment.get()));
    sendEmailUtils.sendEmail(
        String.format(
            "Registrar %s (%s) updated in registry %s environment",
            existingRegistrar.getRegistrarName(), existingRegistrar.getClientId(), environment),
        String.format(
            "The following changes were made in registry %s environment to "
                + "the registrar %s by %s:\n\n%s",
            environment,
            existingRegistrar.getClientId(),
            authResult.userIdForLogging(),
            DiffUtils.prettyPrintDiffedMap(diffs, null)),
        existingContacts.stream()
            .filter(c -> c.getTypes().contains(Type.ADMIN))
            .map(RegistrarContact::getEmailAddress)
            .collect(toImmutableList()));
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
