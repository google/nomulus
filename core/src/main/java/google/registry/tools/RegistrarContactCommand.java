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

package google.registry.tools;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Strings.isNullOrEmpty;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static google.registry.util.CollectionUtils.nullToEmpty;
import static google.registry.util.PreconditionsUtils.checkArgumentNotNull;
import static google.registry.util.PreconditionsUtils.checkArgumentPresent;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.google.common.base.Enums;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import google.registry.model.common.GaeUserIdConverter;
import google.registry.model.registrar.Registrar;
import google.registry.model.registrar.RegistrarContact;
import google.registry.tools.params.OptionalPhoneNumberParameter;
import google.registry.tools.params.PathParameter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import javax.annotation.Nullable;

/** Command for CRUD operations on {@link Registrar} contact list fields. */
@Parameters(
    separators = " =",
    commandDescription = "Create/read/update/delete the various contact lists for a Registrar.")
final class RegistrarContactCommand extends MutatingCommand {

  @Parameter(
      description = "Client identifier of the registrar account.",
      required = true)
  List<String> mainParameters;

  @Parameter(
      names = "--mode",
      description = "Type of operation you want to perform (LIST, CREATE, UPDATE, or DELETE).",
      required = true)
  Mode mode;

  @Nullable
  @Parameter(
      names = "--name",
      description = "Contact name.")
  String name;

  @Nullable
  @Parameter(
      names = "--contact_type",
      description = "Type of communications for this contact; separate multiple with commas."
          + " Allowed values are ABUSE, ADMIN, BILLING, LEGAL, MARKETING, TECH, WHOIS.")
  private List<String> contactTypeNames;

  @Nullable
  @Parameter(names = "--email", description = "Contact email address.")
  String email;

  @Nullable
  @Parameter(
      names = "--registry_lock_email",
      description = "Email address used for registry lock confirmation emails")
  String registryLockEmail;

  @Nullable
  @Parameter(
      names = "--phone",
      description = "E.164 phone number, e.g. +1.2125650666",
      converter = OptionalPhoneNumberParameter.class,
      validateWith = OptionalPhoneNumberParameter.class)
  private Optional<String> phone;

  @Nullable
  @Parameter(
      names = "--fax",
      description = "E.164 fax number, e.g. +1.2125650666",
      converter = OptionalPhoneNumberParameter.class,
      validateWith = OptionalPhoneNumberParameter.class)
  private Optional<String> fax;

  @Nullable
  @Parameter(
      names = "--allow_console_access",
      description = "Enable or disable access to the registrar console for this contact.",
      arity = 1)
  Boolean allowConsoleAccess;

  @Nullable
  @Parameter(
      names = "--visible_in_whois_as_admin",
      description = " Whether this contact is publicly visible in WHOIS results as an "
          + "Admin contact.",
      arity = 1)
  private Boolean visibleInWhoisAsAdmin;

  @Nullable
  @Parameter(
      names = "--visible_in_whois_as_tech",
      description = " Whether this contact is publicly visible in WHOIS results as a "
          + "Tech contact.",
      arity = 1)
  private Boolean visibleInWhoisAsTech;

  @Nullable
  @Parameter(
      names = "--visible_in_domain_whois_as_abuse",
      description = " Whether this contact is publicly visible in WHOIS domain results as the "
          + "registry abuse phone and email. If this flag is set, it will be cleared from all "
          + "other contacts for the same registrar.",
      arity = 1)
  private Boolean visibleInDomainWhoisAsAbuse;

  @Nullable
  @Parameter(
      names = "--allowed_to_set_registry_lock_password",
      description =
          "Allow this contact to set their registry lock password in the console,"
              + " enabling registry lock",
      arity = 1)
  private Boolean allowedToSetRegistryLockPassword;

  @Parameter(
      names = {"-o", "--output"},
      description = "Output file when --mode=LIST",
      validateWith = PathParameter.OutputFile.class)
  private Path output = Paths.get("/dev/stdout");

  enum Mode { LIST, CREATE, UPDATE, DELETE }

  private static final ImmutableSet<Mode> MODES_REQUIRING_CONTACT_SYNC =
      ImmutableSet.of(Mode.CREATE, Mode.UPDATE, Mode.DELETE);

  @Nullable
  private ImmutableSet<RegistrarContact.Type> contactTypes;

  @Override
  protected void init() throws Exception {
    checkArgument(mainParameters.size() == 1,
        "Must specify exactly one client identifier: %s", ImmutableList.copyOf(mainParameters));
    String clientId = mainParameters.get(0);
    Registrar registrar =
        checkArgumentPresent(
            Registrar.loadByClientId(clientId), "Registrar %s not found", clientId);
    // If the contact_type parameter is not specified, we should not make any changes.
    if (contactTypeNames == null) {
      contactTypes = null;
    // It appears that when the user specifies "--contact_type=" with no types following, JCommander
    // sets contactTypeNames to a one-element list containing the empty string. This is strange, but
    // we need to handle this by setting the contact types to the empty set. Also do this if
    // contactTypeNames is empty, which is what I would hope JCommander would return in some future,
    // better world.
    } else if (contactTypeNames.isEmpty()
        || ((contactTypeNames.size() == 1) && contactTypeNames.get(0).isEmpty())) {
      contactTypes = ImmutableSet.of();
    } else {
      contactTypes =
          contactTypeNames
              .stream()
              .map(Enums.stringConverter(RegistrarContact.Type.class))
              .collect(toImmutableSet());
    }
    ImmutableSet<RegistrarContact> contacts = registrar.getContacts();
    Map<String, RegistrarContact> contactsMap = new LinkedHashMap<>();
    for (RegistrarContact rc : contacts) {
      contactsMap.put(rc.getEmailAddress(), rc);
    }
    RegistrarContact oldContact;
    switch (mode) {
      case LIST:
        listContacts(contacts);
        break;
      case CREATE:
        stageEntityChange(null, createContact(registrar));
        if ((visibleInDomainWhoisAsAbuse != null) && visibleInDomainWhoisAsAbuse) {
          unsetOtherWhoisAbuseFlags(contacts, null);
        }
        break;
      case UPDATE:
        oldContact =
            checkNotNull(
                contactsMap.get(checkNotNull(email, "--email is required when --mode=UPDATE")),
                "No contact with the given email: %s",
                email);
        RegistrarContact newContact = updateContact(oldContact, registrar);
        checkArgument(
            !oldContact.getVisibleInDomainWhoisAsAbuse()
                || newContact.getVisibleInDomainWhoisAsAbuse(),
            "Cannot clear visible_in_domain_whois_as_abuse flag, as that would leave no domain"
                + " WHOIS abuse contacts; instead, set the flag on another contact");
        stageEntityChange(oldContact, newContact);
        if ((visibleInDomainWhoisAsAbuse != null) && visibleInDomainWhoisAsAbuse) {
          unsetOtherWhoisAbuseFlags(contacts, oldContact.getEmailAddress());
        }
        break;
      case DELETE:
        oldContact =
            checkNotNull(
                contactsMap.get(checkNotNull(email, "--email is required when --mode=DELETE")),
                "No contact with the given email: %s",
                email);
        checkArgument(
            !oldContact.getVisibleInDomainWhoisAsAbuse(),
            "Cannot delete the domain WHOIS abuse contact; set the flag on another contact first");
        stageEntityChange(oldContact, null);
        break;
      default:
        throw new AssertionError();
    }
    if (MODES_REQUIRING_CONTACT_SYNC.contains(mode)) {
      stageEntityChange(registrar, registrar.asBuilder().setContactsRequireSyncing(true).build());
    }
  }

  private void listContacts(Set<RegistrarContact> contacts) throws IOException {
    List<String> result = new ArrayList<>();
    for (RegistrarContact c : contacts) {
      result.add(c.toStringMultilinePlainText());
    }
    Files.write(output, Joiner.on('\n').join(result).getBytes(UTF_8));
  }

  private RegistrarContact createContact(Registrar registrar) {
    checkArgument(!isNullOrEmpty(name), "--name is required when --mode=CREATE");
    checkArgument(!isNullOrEmpty(email), "--email is required when --mode=CREATE");
    RegistrarContact.Builder builder = new RegistrarContact.Builder();
    builder.setParent(registrar);
    builder.setName(name);
    builder.setEmailAddress(email);
    if (!isNullOrEmpty(registryLockEmail)) {
      builder.setRegistryLockEmailAddress(registryLockEmail);
    }
    if (phone != null) {
      builder.setPhoneNumber(phone.orElse(null));
    }
    if (fax != null) {
      builder.setFaxNumber(fax.orElse(null));
    }
    builder.setTypes(nullToEmpty(contactTypes));

    if (Objects.equals(allowConsoleAccess, Boolean.TRUE)) {
      builder.setGaeUserId(checkArgumentNotNull(
          GaeUserIdConverter.convertEmailAddressToGaeUserId(email),
          String.format("Email address %s is not associated with any GAE ID", email)));
    }
    if (visibleInWhoisAsAdmin != null) {
      builder.setVisibleInWhoisAsAdmin(visibleInWhoisAsAdmin);
    }
    if (visibleInWhoisAsTech != null) {
      builder.setVisibleInWhoisAsTech(visibleInWhoisAsTech);
    }
    if (visibleInDomainWhoisAsAbuse != null) {
      builder.setVisibleInDomainWhoisAsAbuse(visibleInDomainWhoisAsAbuse);
    }
    if (allowedToSetRegistryLockPassword != null) {
      builder.setAllowedToSetRegistryLockPassword(allowedToSetRegistryLockPassword);
    }
    return builder.build();
  }

  private RegistrarContact updateContact(RegistrarContact contact, Registrar registrar) {
    checkNotNull(registrar);
    checkNotNull(email, "--email is required when --mode=UPDATE");
    RegistrarContact.Builder builder = contact.asBuilder().setEmailAddress(email);
    builder.setParent(registrar);
    if (!isNullOrEmpty(name)) {
      builder.setName(name);
    }
    if (!isNullOrEmpty(registryLockEmail)) {
      builder.setRegistryLockEmailAddress(registryLockEmail);
    }
    if (phone != null) {
      builder.setPhoneNumber(phone.orElse(null));
    }
    if (fax != null) {
      builder.setFaxNumber(fax.orElse(null));
    }
    if (contactTypes != null) {
      builder.setTypes(contactTypes);
    }
    if (visibleInWhoisAsAdmin != null) {
      builder.setVisibleInWhoisAsAdmin(visibleInWhoisAsAdmin);
    }
    if (visibleInWhoisAsTech != null) {
      builder.setVisibleInWhoisAsTech(visibleInWhoisAsTech);
    }
    if (visibleInDomainWhoisAsAbuse != null) {
      builder.setVisibleInDomainWhoisAsAbuse(visibleInDomainWhoisAsAbuse);
    }
    if (allowConsoleAccess != null) {
      if (allowConsoleAccess.equals(Boolean.TRUE)) {
        builder.setGaeUserId(checkArgumentNotNull(
            GaeUserIdConverter.convertEmailAddressToGaeUserId(email),
            String.format("Email address %s is not associated with any GAE ID", email)));
      } else {
        builder.setGaeUserId(null);
      }
    }
    if (allowedToSetRegistryLockPassword != null) {
      builder.setAllowedToSetRegistryLockPassword(allowedToSetRegistryLockPassword);
    }
    return builder.build();
  }

  private void unsetOtherWhoisAbuseFlags(
      ImmutableSet<RegistrarContact> contacts, @Nullable String emailAddressNotToChange) {
    for (RegistrarContact contact : contacts) {
      if (!contact.getEmailAddress().equals(emailAddressNotToChange)
          && contact.getVisibleInDomainWhoisAsAbuse()) {
        RegistrarContact newContact =
            contact.asBuilder().setVisibleInDomainWhoisAsAbuse(false).build();
        stageEntityChange(contact, newContact);
      }
    }
  }
}
