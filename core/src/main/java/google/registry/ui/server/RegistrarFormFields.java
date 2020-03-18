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

package google.registry.ui.server;

import static com.google.common.collect.Range.atLeast;
import static com.google.common.collect.Range.atMost;
import static com.google.common.collect.Range.closed;
import static google.registry.util.DomainNameUtils.canonicalizeDomainName;

import com.google.common.base.Ascii;
import com.google.common.base.Splitter;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.net.InternetDomainName;
import com.google.re2j.Pattern;
import google.registry.model.registrar.Registrar;
import google.registry.model.registrar.RegistrarAddress;
import google.registry.model.registrar.RegistrarContact;
import google.registry.ui.forms.FormException;
import google.registry.ui.forms.FormField;
import google.registry.ui.forms.FormFieldException;
import google.registry.ui.forms.FormFields;
import google.registry.util.CidrAddressBlock;
import google.registry.util.X509Utils;
import java.security.cert.CertificateParsingException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import javax.annotation.Nullable;
import org.joda.time.DateTime;

/** Form fields for validating input for the {@code Registrar} class. */
public final class RegistrarFormFields {

  public static final Pattern BASE64_PATTERN = Pattern.compile("[+/a-zA-Z0-9]*");
  public static final Pattern ASCII_PATTERN = Pattern.compile("[[:ascii:]]*");
  public static final String ASCII_ERROR = "Please only use ASCII-US characters.";

  public static final FormField<String, String> NAME_FIELD =
      FormFields.NAME.asBuilderNamed("registrarName")
          .required()
          .build();

  public static final FormField<String, DateTime> LAST_UPDATE_TIME =
      FormFields.LABEL
          .asBuilderNamed("lastUpdateTime")
          .transform(DateTime.class, RegistrarFormFields::parseDateTime)
          .required()
          .build();

  public static final FormField<String, String> EMAIL_ADDRESS_FIELD_REQUIRED =
      FormFields.EMAIL.asBuilderNamed("emailAddress")
          .matches(ASCII_PATTERN, ASCII_ERROR)
          .required()
          .build();

  public static final FormField<String, String> EMAIL_ADDRESS_FIELD_OPTIONAL =
      FormFields.EMAIL.asBuilderNamed("emailAddress")
          .matches(ASCII_PATTERN, ASCII_ERROR)
          .build();

  public static final FormField<String, String> ICANN_REFERRAL_EMAIL_FIELD =
      FormFields.EMAIL.asBuilderNamed("icannReferralEmail")
          .matches(ASCII_PATTERN, ASCII_ERROR)
          .required()
          .build();

  public static final FormField<String, String> PHONE_NUMBER_FIELD =
      FormFields.PHONE_NUMBER.asBuilder()
          .matches(ASCII_PATTERN, ASCII_ERROR)
          .build();

  public static final FormField<String, String> FAX_NUMBER_FIELD =
      FormFields.PHONE_NUMBER.asBuilderNamed("faxNumber")
          .matches(ASCII_PATTERN, ASCII_ERROR)
          .build();

  public static final FormField<String, Registrar.State> STATE_FIELD =
      FormField.named("state")
          .emptyToNull()
          .asEnum(Registrar.State.class)
          .build();

  public static final FormField<List<String>, Set<String>> ALLOWED_TLDS_FIELD =
      FormFields.LABEL.asBuilderNamed("allowedTlds")
          .asSet()
          .build();

  public static final FormField<String, String> WHOIS_SERVER_FIELD =
      FormFields.LABEL.asBuilderNamed("whoisServer")
          .transform(RegistrarFormFields::parseHostname)
          .build();

  public static final FormField<Boolean, Boolean> BLOCK_PREMIUM_NAMES_FIELD =
      FormField.named("blockPremiumNames", Boolean.class)
          .build();

  public static final FormField<String, String> DRIVE_FOLDER_ID_FIELD =
      FormFields.XS_NORMALIZED_STRING.asBuilderNamed("driveFolderId")
          .build();

  public static final FormField<String, String> CLIENT_CERTIFICATE_HASH_FIELD =
      FormField.named("clientCertificateHash")
          .emptyToNull()
          .matches(BASE64_PATTERN, "Field must contain a base64 value.")
          .range(closed(1, 255))
          .build();

  private static final FormField<String, String> X509_PEM_CERTIFICATE =
      FormField.named("certificate")
          .emptyToNull()
          .transform(
              input -> {
                if (input == null) {
                  return null;
                }
                try {
                  X509Utils.loadCertificate(input);
                } catch (CertificateParsingException e) {
                  throw new FormFieldException("Invalid X.509 PEM certificate");
                }
                return input;
              })
          .build();

  public static final FormField<String, String> CLIENT_CERTIFICATE_FIELD =
      X509_PEM_CERTIFICATE.asBuilderNamed("clientCertificate").build();

  public static final FormField<String, String> FAILOVER_CLIENT_CERTIFICATE_FIELD =
      X509_PEM_CERTIFICATE.asBuilderNamed("failoverClientCertificate").build();

  public static final FormField<Long, Long> BILLING_IDENTIFIER_FIELD =
      FormField.named("billingIdentifier", Long.class)
          .range(atLeast(1))
          .build();

  public static final FormField<Long, Long> IANA_IDENTIFIER_FIELD =
      FormField.named("ianaIdentifier", Long.class)
          .range(atLeast(1))
          .build();

  public static final FormField<String, String> URL_FIELD =
      FormFields.MIN_TOKEN.asBuilderNamed("url")
          .build();

  public static final FormField<List<String>, List<CidrAddressBlock>> IP_ADDRESS_WHITELIST_FIELD =
      FormField.named("ipAddressWhitelist")
          .emptyToNull()
          .transform(CidrAddressBlock.class, RegistrarFormFields::parseCidr)
          .asList()
          .build();

  public static final FormField<String, String> PASSWORD_FIELD =
      FormFields.PASSWORD.asBuilderNamed("password")
          .build();

  public static final FormField<String, String> PHONE_PASSCODE_FIELD =
      FormField.named("phonePasscode")
          .emptyToNull()
          .matches(Registrar.PHONE_PASSCODE_PATTERN)
          .build();

  public static final FormField<String, String> CONTACT_NAME_FIELD =
      FormFields.NAME.asBuilderNamed("name")
          .required()
          .build();

  public static final FormField<String, String> CONTACT_EMAIL_ADDRESS_FIELD =
      FormFields.EMAIL.asBuilderNamed("emailAddress")
          .required()
          .build();

  public static final FormField<Boolean, Boolean> CONTACT_VISIBLE_IN_WHOIS_AS_ADMIN_FIELD =
      FormField.named("visibleInWhoisAsAdmin", Boolean.class)
          .build();

  public static final FormField<Boolean, Boolean> CONTACT_VISIBLE_IN_WHOIS_AS_TECH_FIELD =
      FormField.named("visibleInWhoisAsTech", Boolean.class)
          .build();

  public static final FormField<Boolean, Boolean>
      PHONE_AND_EMAIL_VISIBLE_IN_DOMAIN_WHOIS_AS_ABUSE_FIELD =
          FormField.named("visibleInDomainWhoisAsAbuse", Boolean.class).build();

  public static final FormField<String, String> CONTACT_PHONE_NUMBER_FIELD =
      FormFields.PHONE_NUMBER.asBuilder().build();

  public static final FormField<String, String> CONTACT_FAX_NUMBER_FIELD =
      FormFields.PHONE_NUMBER.asBuilderNamed("faxNumber").build();

  public static final FormField<String, String> CONTACT_GAE_USER_ID_FIELD =
      FormFields.NAME.asBuilderNamed("gaeUserId").build();

  public static final FormField<Object, Boolean> CONTACT_ALLOWED_TO_SET_REGISTRY_LOCK_PASSWORD =
      FormField.named("allowedToSetRegistryLockPassword", Object.class)
          .transform(Boolean.class, b -> Boolean.valueOf(Objects.toString(b)))
          .build();

  public static final FormField<String, String> CONTACT_REGISTRY_LOCK_PASSWORD_FIELD =
      FormFields.NAME.asBuilderNamed("registryLockPassword").build();

  public static final FormField<String, Set<RegistrarContact.Type>> CONTACT_TYPES =
      FormField.named("types")
          .uppercased()
          .asEnum(RegistrarContact.Type.class)
          .asSet(Splitter.on(',').omitEmptyStrings().trimResults())
          .build();

  public static final FormField<List<Map<String, ?>>, List<Map<String, ?>>> CONTACTS_AS_MAPS =
      FormField.mapNamed("contacts").asList().build();

  public static final FormField<List<String>, List<String>> I18N_STREET_FIELD =
      FormFields.XS_NORMALIZED_STRING.asBuilderNamed("street")
          .range(closed(1, 255))
          .matches(ASCII_PATTERN, ASCII_ERROR)
          .asList()
          .range(closed(1, 3))
          .required()
          .build();

  public static final FormField<List<String>, List<String>> L10N_STREET_FIELD =
      FormFields.XS_NORMALIZED_STRING.asBuilderNamed("street")
          .range(closed(1, 255))
          .asList()
          .range(closed(1, 3))
          .required()
          .build();

  public static final FormField<String, String> I18N_CITY_FIELD =
      FormFields.NAME.asBuilderNamed("city")
          .matches(ASCII_PATTERN, ASCII_ERROR)
          .required()
          .build();

  public static final FormField<String, String> L10N_CITY_FIELD =
      FormFields.NAME.asBuilderNamed("city")
          .required()
          .build();

  public static final FormField<String, String> I18N_STATE_FIELD =
      FormFields.XS_NORMALIZED_STRING.asBuilderNamed("state")
          .range(atMost(255))
          .matches(ASCII_PATTERN, ASCII_ERROR)
          .build();

  public static final FormField<String, String> L10N_STATE_FIELD =
      FormFields.XS_NORMALIZED_STRING.asBuilderNamed("state")
          .range(atMost(255))
          .build();

  public static final FormField<String, String> I18N_ZIP_FIELD =
      FormFields.XS_TOKEN.asBuilderNamed("zip")
          .range(atMost(16))
          .matches(ASCII_PATTERN, ASCII_ERROR)
          .build();

  public static final FormField<String, String> L10N_ZIP_FIELD =
      FormFields.XS_TOKEN.asBuilderNamed("zip")
          .range(atMost(16))
          .build();

  public static final FormField<String, String> COUNTRY_CODE_FIELD =
      FormFields.COUNTRY_CODE.asBuilder()
          .required()
          .build();

  public static final FormField<Map<String, ?>, RegistrarAddress> L10N_ADDRESS_FIELD =
      FormField.mapNamed("localizedAddress")
          .transform(RegistrarAddress.class, (args) -> toNewAddress(
              args, L10N_STREET_FIELD, L10N_CITY_FIELD, L10N_STATE_FIELD, L10N_ZIP_FIELD))
          .build();

  private static @Nullable RegistrarAddress toNewAddress(
      @Nullable Map<String, ?> args,
      final FormField<List<String>, List<String>> streetField,
      final FormField<String, String> cityField,
      final FormField<String, String> stateField,
      final FormField<String, String> zipField) {
    if (args == null) {
      return null;
    }
    RegistrarAddress.Builder builder = new RegistrarAddress.Builder();
    String countryCode = COUNTRY_CODE_FIELD.extractUntyped(args).get();
    builder.setCountryCode(countryCode);
    streetField
        .extractUntyped(args)
        .ifPresent(streets -> builder.setStreet(ImmutableList.copyOf(streets)));
    cityField.extractUntyped(args).ifPresent(builder::setCity);
    Optional<String> stateFieldValue = stateField.extractUntyped(args);
    if (stateFieldValue.isPresent()) {
      String state = stateFieldValue.get();
      if ("US".equals(countryCode)) {
        state = Ascii.toUpperCase(state);
        if (!StateCode.US_MAP.containsKey(state)) {
          throw new FormFieldException(stateField, "Unknown US state code.");
        }
      }
      builder.setState(state);
    }
    zipField.extractUntyped(args).ifPresent(builder::setZip);
    return builder.build();
  }

  private static CidrAddressBlock parseCidr(String input) {
    try {
      return input != null ? CidrAddressBlock.create(input) : null;
    } catch (IllegalArgumentException e) {
      throw new FormFieldException("Not a valid CIDR notation IP-address block.", e);
    }
  }

  private static @Nullable String parseHostname(@Nullable String input) {
    if (input == null) {
      return null;
    }
    if (!InternetDomainName.isValid(input)) {
      throw new FormFieldException("Not a valid hostname.");
    }
    return canonicalizeDomainName(input);
  }

  public static @Nullable DateTime parseDateTime(@Nullable String input) {
    if (input == null) {
      return null;
    }
    try {
      return DateTime.parse(input);
    } catch (IllegalArgumentException e) {
      throw new FormFieldException("Not a valid ISO date-time string.", e);
    }
  }

  public static ImmutableList<RegistrarContact.Builder> getRegistrarContactBuilders(
      ImmutableSet<RegistrarContact> existingContacts, @Nullable Map<String, ?> args) {
    if (args == null) {
      return ImmutableList.of();
    }
    Optional<List<Map<String, ?>>> contactsAsMaps = CONTACTS_AS_MAPS.extractUntyped(args);
    if (!contactsAsMaps.isPresent()) {
      return ImmutableList.of();
    }
    ImmutableList.Builder<RegistrarContact.Builder> result = new ImmutableList.Builder<>();
    for (Map<String, ?> contactAsMap : contactsAsMaps.get()) {
      String emailAddress =
          CONTACT_EMAIL_ADDRESS_FIELD
              .extractUntyped(contactAsMap)
              .orElseThrow(
                  () -> new IllegalArgumentException("Contacts from UI must have email addresses"));
      // Start with a new builder if the contact didn't previously exist
      RegistrarContact.Builder contactBuilder =
          existingContacts.stream()
              .filter(rc -> rc.getEmailAddress().equals(emailAddress))
              .findFirst()
              .map(RegistrarContact::asBuilder)
              .orElse(new RegistrarContact.Builder());
      applyRegistrarContactArgs(contactBuilder, contactAsMap);
      result.add(contactBuilder);
    }
    return result.build();
  }

  private static void applyRegistrarContactArgs(
      RegistrarContact.Builder builder, Map<String, ?> args) {
    builder.setName(CONTACT_NAME_FIELD.extractUntyped(args).orElse(null));
    builder.setEmailAddress(CONTACT_EMAIL_ADDRESS_FIELD.extractUntyped(args).orElse(null));
    builder.setVisibleInWhoisAsAdmin(
        CONTACT_VISIBLE_IN_WHOIS_AS_ADMIN_FIELD.extractUntyped(args).orElse(false));
    builder.setVisibleInWhoisAsTech(
        CONTACT_VISIBLE_IN_WHOIS_AS_TECH_FIELD.extractUntyped(args).orElse(false));
    builder.setVisibleInDomainWhoisAsAbuse(
        PHONE_AND_EMAIL_VISIBLE_IN_DOMAIN_WHOIS_AS_ABUSE_FIELD.extractUntyped(args).orElse(false));
    builder.setPhoneNumber(CONTACT_PHONE_NUMBER_FIELD.extractUntyped(args).orElse(null));
    builder.setFaxNumber(CONTACT_FAX_NUMBER_FIELD.extractUntyped(args).orElse(null));
    builder.setTypes(CONTACT_TYPES.extractUntyped(args).orElse(ImmutableSet.of()));
    builder.setGaeUserId(CONTACT_GAE_USER_ID_FIELD.extractUntyped(args).orElse(null));
    // The parser is inconsistent with whether it retrieves boolean values as strings or booleans.
    // As a result, use a potentially-redundant converter that can deal with both.
    builder.setAllowedToSetRegistryLockPassword(
        CONTACT_ALLOWED_TO_SET_REGISTRY_LOCK_PASSWORD.extractUntyped(args).orElse(false));

    // Registry lock password does not need to be set every time
    CONTACT_REGISTRY_LOCK_PASSWORD_FIELD
        .extractUntyped(args)
        .ifPresent(
            password -> {
              if (!Strings.isNullOrEmpty(password)) {
                if (password.length() < 8) {
                  throw new FormException(
                      "Registry lock password must be at least 8 characters long");
                }
                builder.setRegistryLockPassword(password);
              }
            });
  }
}
