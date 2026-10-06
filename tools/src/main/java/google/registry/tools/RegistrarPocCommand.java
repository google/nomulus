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
import static google.registry.util.PreconditionsUtils.checkArgumentPresent;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.google.common.base.Enums;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import google.registry.model.registrar.Registrar;
import google.registry.model.registrar.RegistrarPoc;
import google.registry.tools.params.OptionalPhoneNumberParameter;
import google.registry.tools.params.PathParameter;
import google.registry.tools.params.StringListParameter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.annotation.Nullable;

/** Command for CRUD operations on {@link Registrar} contact list fields. */
@SuppressWarnings("OptionalAssignedToNull")
@Parameters(
    separators = " =",
    commandDescription = "Create/read/update/delete the various contact lists for a Registrar.")
final class RegistrarPocCommand extends MutatingCommand {

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
      description =
          "Type of communications for this contact; separate multiple with commas."
              + " Allowed values are ABUSE, ADMIN, BILLING, LEGAL, MARKETING, TECH, WHOIS.",
      listConverter = StringListParameter.class)
  private List<String> contactTypeNames;

  @Nullable
  @Parameter(
      names = "--email",
      description =
          "Contact email address. Required when creating a contact"
              + " and will be used as the console login email, if --login_email is not specified.")
  String email;

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
      names = "--visible_in_rdap_as_admin",
      description = "If this contact is publicly visible in RDAP results as an " + "Admin contact.",
      arity = 1)
  private Boolean visibleInRdapAsAdmin;

  @Nullable
  @Parameter(
      names = "--visible_in_rdap_as_tech",
      description = "If this contact is publicly visible in RDAP results as a " + "Tech contact.",
      arity = 1)
  private Boolean visibleInRdapAsTech;

  @Nullable
  @Parameter(
      names = "--visible_in_domain_rdap_as_abuse",
      description =
          " Whether this contact is publicly visible in RDAP domain results as the "
              + "registry abuse phone and email. If this flag is set, it will be cleared from all "
              + "other contacts for the same registrar.",
      arity = 1)
  private Boolean visibleInDomainRdapAsAbuse;

  @Parameter(
      names = {"-o", "--output"},
      description = "Output file when --mode=LIST",
      validateWith = PathParameter.OutputFile.class)
  private Path output = Paths.get("/dev/stdout");

  enum Mode { LIST, CREATE, UPDATE, DELETE }

  private static final ImmutableSet<Mode> MODES_REQUIRING_CONTACT_SYNC =
      ImmutableSet.of(Mode.CREATE, Mode.UPDATE, Mode.DELETE);

  @Nullable private ImmutableSet<RegistrarPoc.Type> contactTypes;

  @Override
  protected void init() throws Exception {
    checkArgument(mainParameters.size() == 1,
        "Must specify exactly one client identifier: %s", ImmutableList.copyOf(mainParameters));
    String clientId = mainParameters.getFirst();
    Registrar registrar =
        checkArgumentPresent(
            Registrar.loadByRegistrarId(clientId), "Registrar %s not found", clientId);
    // If the contact_type parameter is not specified, we should not make any changes.
    if (contactTypeNames == null) {
      contactTypes = null;
    } else {
      contactTypes =
          contactTypeNames.stream()
              .map(Enums.stringConverter(RegistrarPoc.Type.class))
              .collect(toImmutableSet());
    }
    ImmutableSet<RegistrarPoc> contacts = registrar.getContacts();
    Map<String, RegistrarPoc> contactsMap = new LinkedHashMap<>();
    for (RegistrarPoc rc : contacts) {
      contactsMap.put(rc.getEmailAddress(), rc);
    }
    RegistrarPoc oldContact;
    switch (mode) {
      case LIST -> listContacts(contacts);
      case CREATE -> {
        stageEntityChange(null, createContact(registrar));
        if (visibleInDomainRdapAsAbuse != null && visibleInDomainRdapAsAbuse) {
          unsetOtherRdapAbuseFlags(contacts, null);
        }
      }
      case UPDATE -> {
        oldContact =
            checkNotNull(
                contactsMap.get(checkNotNull(email, "--email is required when --mode=UPDATE")),
                "No contact with the given email: %s",
                email);
        RegistrarPoc newContact = updateContact(oldContact, registrar);
        checkArgument(
            !oldContact.getVisibleInDomainRdapAsAbuse()
                || newContact.getVisibleInDomainRdapAsAbuse(),
            "Cannot clear visible_in_domain_rdap_as_abuse flag, as that would leave no domain"
                + " RDAP abuse contacts; instead, set the flag on another contact");
        stageEntityChange(oldContact, newContact);
        if (visibleInDomainRdapAsAbuse != null && visibleInDomainRdapAsAbuse) {
          unsetOtherRdapAbuseFlags(contacts, oldContact.getEmailAddress());
        }
      }
      case DELETE -> {
        oldContact =
            checkNotNull(
                contactsMap.get(checkNotNull(email, "--email is required when --mode=DELETE")),
                "No contact with the given email: %s",
                email);
        checkArgument(
            !oldContact.getVisibleInDomainRdapAsAbuse(),
            "Cannot delete the domain RDAP abuse contact; set the flag on another contact first");
        stageEntityChange(oldContact, null);
      }
      default -> throw new AssertionError();
    }
    if (MODES_REQUIRING_CONTACT_SYNC.contains(mode)) {
      stageEntityChange(registrar, registrar.asBuilder().setContactsRequireSyncing(true).build());
    }
  }

  private void listContacts(Set<RegistrarPoc> contacts) throws IOException {
    List<String> result = new ArrayList<>();
    for (RegistrarPoc c : contacts) {
      result.add(c.toStringMultilinePlainText());
    }
    Files.writeString(output, Joiner.on('\n').join(result));
  }

  private RegistrarPoc createContact(Registrar registrar) {
    checkArgument(!isNullOrEmpty(name), "--name is required when --mode=CREATE");
    checkArgument(!isNullOrEmpty(email), "--email is required when --mode=CREATE");
    RegistrarPoc.Builder builder = new RegistrarPoc.Builder();
    builder.setRegistrar(registrar);
    builder.setName(name);
    builder.setEmailAddress(email);
    if (phone != null) {
      builder.setPhoneNumber(phone.orElse(null));
    }
    if (fax != null) {
      builder.setFaxNumber(fax.orElse(null));
    }
    builder.setTypes(nullToEmpty(contactTypes));

    if (visibleInRdapAsAdmin != null) {
      builder.setVisibleInRdapAsAdmin(visibleInRdapAsAdmin);
    }
    if (visibleInRdapAsTech != null) {
      builder.setVisibleInRdapAsTech(visibleInRdapAsTech);
    }
    if (visibleInDomainRdapAsAbuse != null) {
      builder.setVisibleInDomainRdapAsAbuse(visibleInDomainRdapAsAbuse);
    }
    return builder.build();
  }

  private RegistrarPoc updateContact(RegistrarPoc contact, Registrar registrar) {
    checkNotNull(registrar);
    checkArgument(!isNullOrEmpty(email), "--email is required when --mode=UPDATE");
    RegistrarPoc.Builder builder =
        contact.asBuilder().setEmailAddress(email).setRegistrar(registrar);
    if (!isNullOrEmpty(name)) {
      builder.setName(name);
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
    if (visibleInRdapAsAdmin != null) {
      builder.setVisibleInRdapAsAdmin(visibleInRdapAsAdmin);
    }
    if (visibleInRdapAsTech != null) {
      builder.setVisibleInRdapAsTech(visibleInRdapAsTech);
    }
    if (visibleInDomainRdapAsAbuse != null) {
      builder.setVisibleInDomainRdapAsAbuse(visibleInDomainRdapAsAbuse);
    }
    return builder.build();
  }

  private void unsetOtherRdapAbuseFlags(
      ImmutableSet<RegistrarPoc> contacts, @Nullable String emailAddressNotToChange) {
    for (RegistrarPoc contact : contacts) {
      if (!contact.getEmailAddress().equals(emailAddressNotToChange)
          && contact.getVisibleInDomainRdapAsAbuse()) {
        RegistrarPoc newContact = contact.asBuilder().setVisibleInDomainRdapAsAbuse(false).build();
        stageEntityChange(contact, newContact);
      }
    }
  }
}
