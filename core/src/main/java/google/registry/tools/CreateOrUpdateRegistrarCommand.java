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
import static com.google.common.base.Strings.isNullOrEmpty;
import static com.google.common.base.Verify.verify;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static google.registry.util.DomainNameUtils.canonicalizeHostname;
import static google.registry.util.RegistrarUtils.normalizeRegistrarName;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.joda.time.DateTimeZone.UTC;

import com.beust.jcommander.Parameter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import google.registry.flows.certs.CertificateChecker;
import google.registry.model.registrar.Registrar;
import google.registry.model.registrar.RegistrarAddress;
import google.registry.tools.params.KeyValueMapParameter.CurrencyUnitToStringMap;
import google.registry.tools.params.OptionalLongParameter;
import google.registry.tools.params.OptionalPhoneNumberParameter;
import google.registry.tools.params.OptionalStringParameter;
import google.registry.tools.params.PathParameter.InputFile;
import google.registry.tools.params.StringListParameter;
import google.registry.util.CidrAddressBlock;
import jakarta.inject.Inject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import javax.annotation.Nullable;
import org.joda.money.CurrencyUnit;
import org.joda.time.DateTime;

/** Shared base class for commands to create or update a {@link Registrar}. */
abstract class CreateOrUpdateRegistrarCommand extends MutatingCommand {

  @Inject CertificateChecker certificateChecker;

  @Parameter(description = "Client identifier of the registrar account", required = true)
  List<String> mainParameters;

  @Parameter(names = "--registrar_type", description = "Type of the registrar")
  Registrar.Type registrarType;

  @Nullable
  @Parameter(names = "--registrar_state", description = "Initial state of the registrar")
  Registrar.State registrarState;

  @Parameter(
      names = "--allowed_tlds",
      description = "Comma-delimited list of TLDs which the registrar is allowed to use",
      listConverter = StringListParameter.class)
  List<String> allowedTlds = new ArrayList<>();

  @Parameter(
      names = "--add_allowed_tlds",
      description = "Comma-delimited list of TLDs to add to TLDs a registrar is allowed to use",
      listConverter = StringListParameter.class)
  List<String> addAllowedTlds = new ArrayList<>();

  @Nullable
  @Parameter(
      names = "--password",
      description = "Password for the registrar account")
  String password;

  @Nullable
  @Parameter(
      names = "--name",
      description = "Name of the registrar")
  String registrarName;

  @Nullable
  @Parameter(names = "--email", description = "Email address of registrar")
  String email;

  @Nullable
  @Parameter(
      names = "--icann_referral_email",
      description = "ICANN referral email, as specified in registrar contract")
  String icannReferralEmail;

  @Nullable
  @Parameter(
      names = "--url",
      description = "URL of registrar's website",
      converter = OptionalStringParameter.class,
      validateWith = OptionalStringParameter.class)
  private Optional<String> url;

  @Nullable
  @Parameter(
      names = "--phone",
      description = "E.164 phone number, e.g. +1.2125650666",
      converter = OptionalPhoneNumberParameter.class,
      validateWith = OptionalPhoneNumberParameter.class)
  Optional<String> phone;

  @Nullable
  @Parameter(
      names = "--fax",
      description = "E.164 fax number, e.g. +1.2125650666",
      converter = OptionalPhoneNumberParameter.class,
      validateWith = OptionalPhoneNumberParameter.class)
  Optional<String> fax;

  @Nullable
  @Parameter(
      names = "--cert_file",
      description = "File containing client certificate (X.509 PEM)",
      validateWith = InputFile.class)
  Path clientCertificateFilename;

  @Parameter(
      names = "--rotate_primary_cert",
      description =
          "Used together with --cert_file when updating an registrar. "
              + "If set, current cert is saved as failover.")
  private Boolean rotatePrimaryCert = Boolean.FALSE;

  @Nullable
  @Parameter(
      names = "--failover_cert_file",
      description = "File containing failover client certificate (X.509 PEM)",
      validateWith = InputFile.class)
  Path failoverClientCertificateFilename;

  @Parameter(
      names = "--ip_allow_list",
      description = "Comma-delimited list of IP ranges. An empty string clears the allow list.",
      listConverter = StringListParameter.class)
  List<String> ipAllowList;

  @Nullable
  @Parameter(
      names = "--iana_id",
      description = "Registrar IANA ID",
      converter = OptionalLongParameter.class,
      validateWith = OptionalLongParameter.class)
  Optional<Long> ianaId;

  @Nullable
  @Parameter(
      names = "--po_number",
      description = "Purchase Order number used for billing invoices",
      converter = OptionalStringParameter.class,
      validateWith = OptionalStringParameter.class)
  private Optional<String> poNumber;

  @Nullable
  @Parameter(
      names = "--billing_account_map",
      description =
          "Registrar Billing Account key-value pairs (formatted as key=value[,key=value...]), "
              + "where key is a currency unit (USD, JPY, etc) and value is the registrar's billing "
              + "account id for that currency. During update, only the pairs that need updating "
              + "need to be provided, except when an empty string is provided, in which case the"
              + "entire map is nullified.",
      converter = CurrencyUnitToStringMap.class,
      validateWith = CurrencyUnitToStringMap.class)
  private Map<CurrencyUnit, String> billingAccountMap;

  @Nullable
  @Parameter(
      names = "--street",
      variableArity = true,
      description = "Street lines of address. Can take up to 3 lines.")
  List<String> street;

  @Nullable
  @Parameter(
      names = "--city",
      description = "City of address")
  String city;

  @Nullable
  @Parameter(
      names = "--state",
      description = "State/Province of address. The value \"null\" clears this field.")
  String state;

  @Nullable
  @Parameter(
      names = "--zip",
      description = "Postal code of address. The value \"null\" clears this field.")
  String zip;

  @Nullable
  @Parameter(
      names = "--cc",
      description = "Country code of address")
  String countryCode;

  @Nullable
  @Parameter(
      names = "--block_premium",
      description = "Whether premium name registration should be blocked on this registrar",
      arity = 1)
  private Boolean blockPremiumNames;

  @Nullable
  @Parameter(
      names = "--sync_groups",
      description = "Whether this registrar's groups should be updated at the next scheduled sync",
      arity = 1)
  private Boolean contactsRequireSyncing;

  @Nullable
  @Parameter(
      names = "--registry_lock_allowed",
      description = "Whether this registrar is allowed to use registry lock",
      arity = 1)
  private Boolean registryLockAllowed;

  @Nullable
  @Parameter(
      names = "--drive_folder_id",
      description = "Id (not full URL) of this registrar's folder in Drive",
      converter = OptionalStringParameter.class,
      validateWith = OptionalStringParameter.class)
  Optional<String> driveFolderId;

  @Nullable
  @Parameter(
      names = "--passcode",
      description = "Telephone support passcode")
  String phonePasscode;

  @Nullable
  @Parameter(
      names = "--whois",
      description = "Hostname of registrar WHOIS server. (Default: whois.nic.google)")
  String whoisServer;

  @Parameter(
      names = "--rdap_servers",
      description =
          "Comma-delimited list of RDAP servers. An empty argument clears the list."
              + " Note that for real registrars this could get overridden periodically by"
              + " ICANN-registered values.",
      listConverter = StringListParameter.class)
  List<String> rdapServers = new ArrayList<>();

  /** Returns the existing registrar (for update) or null (for creates). */
  @Nullable
  abstract Registrar getOldRegistrar(String clientId);

  abstract void checkModifyAllowedTlds(@Nullable Registrar oldRegistrar);

  protected void initRegistrarCommand() {}

  @Override
  protected final void init() throws Exception {
    initRegistrarCommand();
    DateTime now = DateTime.now(UTC);
    for (String clientId : mainParameters) {
      Registrar oldRegistrar = getOldRegistrar(clientId);
      Registrar.Builder builder =
          (oldRegistrar == null)
              ? new Registrar.Builder().setRegistrarId(clientId)
              : oldRegistrar.asBuilder();

      if (!isNullOrEmpty(password)) {
        builder.setPassword(password);
      }
      if (!isNullOrEmpty(registrarName)) {
        builder.setRegistrarName(registrarName);
      }
      if (!isNullOrEmpty(email)) {
        builder.setEmailAddress(email);
      } else if (!isNullOrEmpty(icannReferralEmail) && oldRegistrar == null) {
        // On creates, fall back to ICANN referral email (if present).
        builder.setEmailAddress(icannReferralEmail);
      }
      Optional.ofNullable(url).ifPresent(u -> builder.setUrl(u.orElse(null)));
      Optional.ofNullable(phone).ifPresent(p -> builder.setPhoneNumber(p.orElse(null)));
      Optional.ofNullable(fax).ifPresent(f -> builder.setFaxNumber(f.orElse(null)));
      Optional.ofNullable(registrarType).ifPresent(builder::setType);
      Optional.ofNullable(registrarState).ifPresent(builder::setState);
      Optional.ofNullable(driveFolderId).ifPresent(d -> builder.setDriveFolderId(d.orElse(null)));

      if (!allowedTlds.isEmpty() || !addAllowedTlds.isEmpty()) {
        checkModifyAllowedTlds(oldRegistrar);
      }
      if (!allowedTlds.isEmpty()) {
        checkArgument(
            addAllowedTlds.isEmpty(), "Can't specify both --allowedTlds and --addAllowedTlds");
        ImmutableSet.Builder<String> allowedTldsBuilder = new ImmutableSet.Builder<>();
        for (String allowedTld : allowedTlds) {
          allowedTldsBuilder.add(canonicalizeHostname(allowedTld));
        }
        builder.setAllowedTlds(allowedTldsBuilder.build());
      }
      if (!addAllowedTlds.isEmpty()) {
        ImmutableSet.Builder<String> allowedTldsBuilder = new ImmutableSet.Builder<>();
        if (oldRegistrar != null) {
          allowedTldsBuilder.addAll(oldRegistrar.getAllowedTlds());
        }
        for (String allowedTld : addAllowedTlds) {
          allowedTldsBuilder.add(canonicalizeHostname(allowedTld));
        }
        builder.setAllowedTlds(allowedTldsBuilder.build());
      }
      if (ipAllowList != null) {
        builder.setIpAddressAllowList(
            ipAllowList.stream().map(CidrAddressBlock::create).collect(toImmutableList()));
      }
      if (clientCertificateFilename != null) {
        String asciiCert = Files.readString(clientCertificateFilename, US_ASCII);
        // An empty certificate file is allowed in order to provide a functionality for removing an
        // existing certificate without providing a replacement. An uploaded empty certificate file
        // will prevent the registrar from being able to establish EPP connections.
        if (!asciiCert.equals("")) {
          certificateChecker.validateCertificate(asciiCert);
        }
        if (rotatePrimaryCert) {
          verify(
              oldRegistrar != null,
              "--rotate_primary_cert cannot be set by create_registrar command.");
          verify(
              oldRegistrar.getClientCertificate().isPresent(),
              "Primary cert is absent. Rotation may remove a failover certificate still in use.");
          builder.setFailoverClientCertificate(oldRegistrar.getClientCertificate().get(), now);
        }
        builder.setClientCertificate(asciiCert, now);
      }
      if (rotatePrimaryCert && clientCertificateFilename == null) {
        throw new IllegalArgumentException("--rotate_primary_cert must be used with --cert_file.");
      }

      if (failoverClientCertificateFilename != null) {
        String asciiCert = Files.readString(failoverClientCertificateFilename, US_ASCII);
        if (!asciiCert.equals("")) {
          certificateChecker.validateCertificate(asciiCert);
        }
        builder.setFailoverClientCertificate(asciiCert, now);
      }
      Optional.ofNullable(ianaId).ifPresent(i -> builder.setIanaIdentifier(i.orElse(null)));
      Optional.ofNullable(poNumber).ifPresent(builder::setPoNumber);
      if (billingAccountMap != null) {
        LinkedHashMap<CurrencyUnit, String> newBillingAccountMap = new LinkedHashMap<>();
        if (oldRegistrar != null
            && oldRegistrar.getBillingAccountMap() != null
            && !billingAccountMap.isEmpty()) {
          newBillingAccountMap.putAll(oldRegistrar.getBillingAccountMap());
        }
        newBillingAccountMap.putAll(billingAccountMap);
        builder.setBillingAccountMap(newBillingAccountMap);
      }
      List<Object> streetAddressFields = Arrays.asList(street, city, state, zip, countryCode);
      checkArgument(
          streetAddressFields.stream().anyMatch(Objects::isNull)
              == streetAddressFields.stream().allMatch(Objects::isNull),
          "Must specify all fields of address");
      if (street != null) {
        // We always set the localized address for now. That should be safe to do since it supports
        // unrestricted UTF-8.
        builder.setLocalizedAddress(new RegistrarAddress.Builder()
            .setStreet(ImmutableList.copyOf(street))
            .setCity(city)
            .setState("null".equals(state) ? null : state)
            .setZip("null".equals(zip) ? null : zip)
            .setCountryCode(countryCode)
            .build());
      }
      Optional.ofNullable(blockPremiumNames).ifPresent(builder::setBlockPremiumNames);
      Optional.ofNullable(contactsRequireSyncing).ifPresent(builder::setContactsRequireSyncing);
      Optional.ofNullable(registryLockAllowed).ifPresent(builder::setRegistryLockAllowed);
      Optional.ofNullable(phonePasscode).ifPresent(builder::setPhonePasscode);
      Optional.ofNullable(icannReferralEmail).ifPresent(builder::setIcannReferralEmail);
      Optional.ofNullable(whoisServer).ifPresent(builder::setWhoisServer);

      if (!rdapServers.isEmpty()) {
        // If we only have empty strings, then remove all the RDAP servers
        // This is to differentiate between "I didn't set the rdapServers because I don't want to
        // change them" and "I set the RDAP servers to an empty string because I want no RDAP
        // servers".
        builder.setRdapBaseUrls(
            rdapServers.stream().filter(server -> !server.isEmpty()).collect(toImmutableSet()));
      }

      // If the registrarName is being set, verify that it is either null or it normalizes uniquely.
      String oldRegistrarName = (oldRegistrar == null) ? null : oldRegistrar.getRegistrarName();
      if (registrarName != null && !registrarName.equals(oldRegistrarName)) {
        String normalizedName = normalizeRegistrarName(registrarName);
        for (Registrar registrar : Registrar.loadAll()) {
          if (registrar.getRegistrarName() != null) {
            checkArgument(
                !normalizedName.equals(normalizeRegistrarName(registrar.getRegistrarName())),
                "The registrar name %s normalizes identically to existing registrar name %s",
                registrarName,
                registrar.getRegistrarName());
          }
        }
      }

      Registrar newRegistrar = builder.build();

      // Apply some extra validation when creating a new REAL registrar or changing the type of a
      // registrar to REAL. Leave existing REAL registrars alone.
      if (Registrar.Type.REAL.equals(registrarType)) {
        // Require a phone passcode.
        checkArgument(
            newRegistrar.getPhonePasscode() != null, "--passcode is required for REAL registrars.");
      }

      stageEntityChange(oldRegistrar, newRegistrar);
    }
  }
}
