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

package google.registry.model;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static google.registry.model.ofy.ObjectifyService.ofy;
import static google.registry.model.registry.Registry.TldState.GENERAL_AVAILABILITY;
import static google.registry.model.registry.Registry.TldState.START_DATE_SUNRISE;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.util.DateTimeUtils.START_OF_TIME;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Sets;
import com.google.common.collect.Streams;
import com.googlecode.objectify.Key;
import google.registry.config.RegistryEnvironment;
import google.registry.model.common.GaeUserIdConverter;
import google.registry.model.pricing.StaticPremiumListPricingEngine;
import google.registry.model.registrar.Registrar;
import google.registry.model.registrar.RegistrarAddress;
import google.registry.model.registrar.RegistrarContact;
import google.registry.model.registry.Registry;
import google.registry.model.registry.Registry.TldState;
import google.registry.model.registry.label.PremiumList;
import google.registry.model.registry.label.PremiumListDualDao;
import google.registry.util.CidrAddressBlock;
import java.util.Collection;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.joda.money.CurrencyUnit;
import org.joda.money.Money;
import org.joda.time.DateTime;
import org.joda.time.Duration;

/**
 * Class to help build and persist all the OT&amp;E entities in Datastore.
 *
 * <p>This includes the TLDs (Registries), Registrars, and the RegistrarContacts that can access the
 * web console.
 *
 * <p>This class is basically a "builder" for the parameters needed to generate the OT&amp;E
 * entities. Nothing is created until you call {@link #buildAndPersist}.
 *
 * <p>Usage example:
 *
 * <pre>{@code
 * OteAccountBuilder.forClientId("example")
 *     .addContact("contact@email.com") // OPTIONAL
 *     .setPassword("password") // OPTIONAL
 *     .setCertificateHash(certificateHash) // OPTIONAL
 *     .setIpAllowList(ImmutableList.of("1.1.1.1", "2.2.2.0/24")) // OPTIONAL
 *     .buildAndPersist();
 * }</pre>
 */
public final class OteAccountBuilder {

  /**
   * Validation regex for registrar base client IDs (3-14 lowercase alphanumeric characters).
   *
   * <p>The base client ID is appended with numbers to create four different test registrar accounts
   * (e.g. reg-1, reg-3, reg-4, reg-5).  Registrar client IDs are of type clIDType in eppcom.xsd
   * which is limited to 16 characters, hence the limit of 14 here to account for the dash and
   * numbers.
   *
   * <p>The base client ID is also used to generate the OT&E TLDs, hence the restriction to
   * lowercase alphanumeric characters.
   */
  private static final Pattern REGISTRAR_PATTERN = Pattern.compile("^[a-z0-9]{3,14}$");

  // Durations are short so that registrars can test with quick transfer (etc.) turnaround.
  private static final Duration SHORT_ADD_GRACE_PERIOD = Duration.standardMinutes(60);
  private static final Duration SHORT_REDEMPTION_GRACE_PERIOD = Duration.standardMinutes(10);
  private static final Duration SHORT_PENDING_DELETE_LENGTH = Duration.standardMinutes(5);

  private static final String DEFAULT_PREMIUM_LIST = "default_sandbox_list";

  private static final RegistrarAddress DEFAULT_ADDRESS =
      new RegistrarAddress.Builder()
          .setStreet(ImmutableList.of("e-street"))
          .setCity("Neverland")
          .setState("NY")
          .setCountryCode("US")
          .setZip("55555")
          .build();

  private static final ImmutableSortedMap<DateTime, Money> EAP_FEE_SCHEDULE =
      ImmutableSortedMap.of(
          new DateTime(0),
          Money.of(CurrencyUnit.USD, 0),
          DateTime.parse("2018-03-01T00:00:00Z"),
          Money.of(CurrencyUnit.USD, 100),
          DateTime.parse("2030-03-01T00:00:00Z"),
          Money.of(CurrencyUnit.USD, 0));

  private final ImmutableMap<String, String> clientIdToTld;
  private final Registry sunriseTld;
  private final Registry gaTld;
  private final Registry eapTld;
  private final ImmutableList.Builder<RegistrarContact> contactsBuilder =
      new ImmutableList.Builder<>();

  private ImmutableList<Registrar> registrars;
  private boolean replaceExisting = false;

  private OteAccountBuilder(String baseClientId) {
    checkState(
        RegistryEnvironment.get() != RegistryEnvironment.PRODUCTION,
        "Can't setup OT&E in production");
    clientIdToTld = createClientIdToTldMap(baseClientId);
    sunriseTld = createTld(baseClientId + "-sunrise", START_DATE_SUNRISE, false, 0);
    gaTld = createTld(baseClientId + "-ga", GENERAL_AVAILABILITY, false, 2);
    eapTld = createTld(baseClientId + "-eap", GENERAL_AVAILABILITY, true, 3);
    registrars =
        clientIdToTld.keySet().stream()
            .map(OteAccountBuilder::createRegistrar)
            .collect(toImmutableList());
  }

  /**
   * Creates an OteAccountBuilder for the given base client ID.
   *
   * @param baseClientId the base clientId which will help name all the entities we create. Normally
   * is the same as the "prod" clientId designated for this registrar.
   */
  public static OteAccountBuilder forClientId(String baseClientId) {
    return new OteAccountBuilder(baseClientId);
  }

  /**
   * Set whether to replace any conflicting existing entities.
   *
   * <p>If true, any existing entity that conflicts with the entities we want to create will be
   * replaced with the newly created data.
   *
   * <p>If false, encountering an existing entity that conflicts with one we want to create will
   * throw an exception during {@link #buildAndPersist}.
   *
   * <p>NOTE that if we fail, no entities are created (the creation is atomic).
   *
   * <p>Default is false (failing if entities exist)
   */
  public OteAccountBuilder setReplaceExisting(boolean replaceExisting) {
    this.replaceExisting = replaceExisting;
    return this;
  }

  /**
   * Adds a RegistrarContact with Web Console access.
   *
   * <p>NOTE: can be called more than once, adding multiple contacts. Each contact will have access
   * to all OT&amp;E Registrars.
   *
   * @param email the contact email that will have web-console access to all the Registrars. Must be
   *     from "our G Suite domain" (we have to be able to get its GaeUserId)
   */
  public OteAccountBuilder addContact(String email) {
    String gaeUserId =
        checkNotNull(
            GaeUserIdConverter.convertEmailAddressToGaeUserId(email),
            "Email address %s is not associated with any GAE ID",
            email);
    registrars.forEach(
        registrar -> contactsBuilder.add(createRegistrarContact(email, gaeUserId, registrar)));
    return this;
  }

  /**
   * Apply a function on all the OT&amp;E Registrars.
   *
   * <p>Use this to set up registrar fields.
   *
   * <p>NOTE: DO NOT change anything that would affect the {@link Key#create} result on Registrars.
   * If you want to make this function public, add a check that the Key.create on the registrars
   * hasn't changed.
   *
   * @param func a function setting the requested fields on Registrar Builders. Will be applied to
   *     all the Registrars.
   */
  private OteAccountBuilder transformRegistrars(
      Function<Registrar.Builder, Registrar.Builder> func) {
    registrars =
        registrars.stream()
            .map(Registrar::asBuilder)
            .map(func)
            .map(Registrar.Builder::build)
            .collect(toImmutableList());
    return this;
  }

  /** Sets the EPP login password for all the OT&amp;E Registrars. */
  public OteAccountBuilder setPassword(String password) {
    return transformRegistrars(builder -> builder.setPassword(password));
  }

  /** Sets the client certificate to all the OT&amp;E Registrars. */
  public OteAccountBuilder setCertificate(String asciiCert, DateTime now) {
    return transformRegistrars(builder -> builder.setClientCertificate(asciiCert, now));
  }

  /** Sets the IP allow list to all the OT&amp;E Registrars. */
  public OteAccountBuilder setIpAllowList(Collection<String> ipAllowList) {
    ImmutableList<CidrAddressBlock> ipAddressAllowList =
        ipAllowList.stream().map(CidrAddressBlock::create).collect(toImmutableList());
    return transformRegistrars(builder -> builder.setIpAddressAllowList(ipAddressAllowList));
  }

  /**
   * Persists all the OT&amp;E entities to datastore.
   *
   * @return map from the new clientIds created to the new TLDs they have access to. Can be used to
   *     go over all the newly created Registrars / Registries / RegistrarContacts if any
   *     post-creation work is needed.
   */
  public ImmutableMap<String, String> buildAndPersist() {
    // save all the entitiesl in a single transaction
    tm().transact(this::saveAllEntities);
    return clientIdToTld;
  }

  /**
   * Return map from the OT&amp;E clientIds we will create to the new TLDs they will have access to.
   */
  public ImmutableMap<String, String> getClientIdToTldMap() {
    return clientIdToTld;
  }

  /** Saves all the OT&amp;E entities we created. */
  private void saveAllEntities() {
    tm().assertInTransaction();

    // use ImmutableObject instead of Registry so that the Key generation doesn't break
    ImmutableList<ImmutableObject> registries = ImmutableList.of(sunriseTld, gaTld, eapTld);
    ImmutableList<RegistrarContact> contacts = contactsBuilder.build();

    if (!replaceExisting) {
      ImmutableList<Key<ImmutableObject>> keys =
          Streams.concat(registries.stream(), registrars.stream(), contacts.stream())
              .map(Key::create)
              .collect(toImmutableList());
      Set<Key<ImmutableObject>> existingKeys = ofy().load().keys(keys).keySet();
      checkState(
          existingKeys.isEmpty(),
          "Found existing object(s) conflicting with OT&E objects: %s",
          existingKeys);
    }
    // Save the Registries (TLDs) first
    ofy().save().entities(registries).now();
    // Now we can set the allowedTlds for the registrars
    registrars = registrars.stream().map(this::addAllowedTld).collect(toImmutableList());
    // and we can save the registrars and contacts!
    ofy().save().entities(registrars);
    ofy().save().entities(contacts);
  }

  private Registrar addAllowedTld(Registrar registrar) {
    String tld = clientIdToTld.get(registrar.getClientId());
    if (registrar.getAllowedTlds().contains(tld)) {
      return registrar;
    }
    return registrar
        .asBuilder()
        .setAllowedTldsUncached(Sets.union(registrar.getAllowedTlds(), ImmutableSet.of(tld)))
        .build();
  }

  private static Registry createTld(
      String tldName,
      TldState initialTldState,
      boolean isEarlyAccess,
      int roidSuffix) {
    String tldNameAlphaNumerical = tldName.replaceAll("[^a-z0-9]", "");
    Optional<PremiumList> premiumList = PremiumListDualDao.getLatestRevision(DEFAULT_PREMIUM_LIST);
    checkState(premiumList.isPresent(), "Couldn't find premium list %s.", DEFAULT_PREMIUM_LIST);
    Registry.Builder builder =
        new Registry.Builder()
            .setTldStr(tldName)
            .setPremiumPricingEngine(StaticPremiumListPricingEngine.NAME)
            .setTldStateTransitions(ImmutableSortedMap.of(START_OF_TIME, initialTldState))
            .setDnsWriters(ImmutableSet.of("VoidDnsWriter"))
            .setPremiumList(premiumList.get())
            .setRoidSuffix(
                String.format(
                    "%S%X",
                    tldNameAlphaNumerical.substring(0, Math.min(tldNameAlphaNumerical.length(), 7)),
                    roidSuffix))
            .setAddGracePeriodLength(SHORT_ADD_GRACE_PERIOD)
            .setPendingDeleteLength(SHORT_PENDING_DELETE_LENGTH)
            .setRedemptionGracePeriodLength(SHORT_REDEMPTION_GRACE_PERIOD);
    if (isEarlyAccess) {
      builder.setEapFeeSchedule(EAP_FEE_SCHEDULE);
    }
    return builder.build();
  }

  /**
   * Creates the Registrar without the allowedTlds set - because we can't set allowedTlds before the
   * TLD is saved.
   */
  private static Registrar createRegistrar(String registrarName) {
    return new Registrar.Builder()
        .setClientId(registrarName)
        .setRegistrarName(registrarName)
        .setType(Registrar.Type.OTE)
        .setLocalizedAddress(DEFAULT_ADDRESS)
        .setEmailAddress("foo@neverland.com")
        .setFaxNumber("+1.2125550100")
        .setPhoneNumber("+1.2125550100")
        .setIcannReferralEmail("nightmare@registrar.test")
        .setState(Registrar.State.ACTIVE)
        .build();
  }

  private static RegistrarContact createRegistrarContact(
      String email, String gaeUserId, Registrar registrar) {
    return new RegistrarContact.Builder()
        .setParent(registrar)
        .setName(email)
        .setEmailAddress(email)
        .setGaeUserId(gaeUserId)
        .build();
  }

  /** Returns the ClientIds of the OT&amp;E, with the TLDs each has access to. */
  public static ImmutableMap<String, String> createClientIdToTldMap(String baseClientId) {
    checkArgument(
        REGISTRAR_PATTERN.matcher(baseClientId).matches(),
        "Invalid registrar name: %s",
        baseClientId);
    return new ImmutableMap.Builder<String, String>()
        .put(baseClientId + "-1", baseClientId + "-sunrise")
        // The -2 registrar no longer exists because landrush no longer exists.
        .put(baseClientId + "-3", baseClientId + "-ga")
        .put(baseClientId + "-4", baseClientId + "-ga")
        .put(baseClientId + "-5", baseClientId + "-eap")
        .build();
  }

  /** Returns the base client ID that correspond to a given OT&amp;E client ID. */
  public static String getBaseClientId(String oteClientId) {
    int index = oteClientId.lastIndexOf('-');
    checkArgument(index > 0, "Invalid OT&E client ID: %s", oteClientId);
    String baseClientId = oteClientId.substring(0, index);
    checkArgument(
        createClientIdToTldMap(baseClientId).containsKey(oteClientId),
        "ID %s is not one of the OT&E client IDs for base %s",
        oteClientId,
        baseClientId);
    return baseClientId;
  }
}
