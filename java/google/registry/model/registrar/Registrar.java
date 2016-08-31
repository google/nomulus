// Copyright 2016 The Domain Registry Authors. All Rights Reserved.
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

package google.registry.model.registrar;

import static com.google.common.base.MoreObjects.firstNonNull;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Predicates.equalTo;
import static com.google.common.base.Predicates.in;
import static com.google.common.base.Predicates.notNull;
import static com.google.common.base.Strings.emptyToNull;
import static com.google.common.base.Strings.nullToEmpty;
import static com.google.common.collect.Sets.immutableEnumSet;
import static com.google.common.io.BaseEncoding.base64;
import static google.registry.model.common.EntityGroupRoot.getCrossTldKey;
import static google.registry.model.ofy.ObjectifyService.ofy;
import static google.registry.model.ofy.Ofy.RECOMMENDED_MEMCACHE_EXPIRATION;
import static google.registry.model.registry.Registries.assertTldExists;
import static google.registry.util.CollectionUtils.nullToEmptyImmutableCopy;
import static google.registry.util.CollectionUtils.nullToEmptyImmutableSortedCopy;
import static google.registry.util.X509Utils.getCertificateHash;
import static google.registry.util.X509Utils.loadCertificate;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import com.google.common.base.Supplier;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Sets;
import com.google.re2j.Pattern;
import com.googlecode.objectify.Key;
import com.googlecode.objectify.Work;
import com.googlecode.objectify.annotation.Cache;
import com.googlecode.objectify.annotation.Entity;
import com.googlecode.objectify.annotation.Id;
import com.googlecode.objectify.annotation.IgnoreSave;
import com.googlecode.objectify.annotation.Index;
import com.googlecode.objectify.annotation.Parent;
import com.googlecode.objectify.condition.IfNull;
import google.registry.config.RegistryEnvironment;
import google.registry.model.Buildable;
import google.registry.model.CreateAutoTimestamp;
import google.registry.model.ImmutableObject;
import google.registry.model.JsonMapBuilder;
import google.registry.model.Jsonifiable;
import google.registry.model.UpdateAutoTimestamp;
import google.registry.model.common.EntityGroupRoot;
import google.registry.util.CidrAddressBlock;
import google.registry.util.NonFinalForTesting;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.cert.CertificateParsingException;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import javax.annotation.Nullable;
import org.joda.time.DateTime;

/** Information about a registrar. */
@Cache(expirationSeconds = RECOMMENDED_MEMCACHE_EXPIRATION)
@Entity
public class Registrar extends ImmutableObject implements Buildable, Jsonifiable {

  /** Represents the type of a registrar entity. */
  public enum Type {
    /** A real-world, third-party registrar.  Should have non-null IANA and billing IDs. */
    REAL(Predicates.<Long>notNull()),

    /**
     * A registrar account used by a real third-party registrar undergoing operational testing
     * and evaluation. Should only be created in sandbox, and should have null IANA/billing IDs.
     */
    OTE(Predicates.<Long>isNull()),

    /**
     * A registrar used for predelegation testing.  Should have a null billing ID.  The IANA ID
     * should be either 9995 or 9996, which are reserved for predelegation testing.
     */
    PDT(in(ImmutableSet.of(9995L, 9996L))),

    /**
     * A registrar used for external monitoring by ICANN.  Should have IANA ID 9997 and a null
     * billing ID.
     */
    EXTERNAL_MONITORING(equalTo(9997L)),

    /**
     * A registrar used for when the registry acts as a registrar.  Must have either IANA ID
     * 9998 (for billable transactions) or 9999 (for non-billable transactions). */
    // TODO(b/13786188): determine what billing ID for this should be, if any.
    INTERNAL(in(ImmutableSet.of(9998L, 9999L))),

    /** A registrar used for internal monitoring.  Should have null IANA/billing IDs. */
    MONITORING(Predicates.<Long>isNull()),

    /** A registrar used for internal testing.  Should have null IANA/billing IDs. */
    TEST(Predicates.<Long>isNull());

    /**
     * Predicate for validating IANA IDs for this type of registrar.
     *
     * @see "http://www.iana.org/assignments/registrar-ids/registrar-ids.txt"
     */
    private final Predicate<Long> ianaIdValidator;

    private Type(Predicate<Long> ianaIdValidator) {
      this.ianaIdValidator = ianaIdValidator;
    }

    /** Returns true if the given IANA identifier is valid for this registrar type. */
    public boolean isValidIanaId(Long ianaId) {
      return ianaIdValidator.apply(ianaId);
    }
  }

  /** Represents the state of a persisted registrar entity. */
  public enum State {
    /**
     * This registrar is provisioned and may have access to the testing environment, but is not yet
     * allowed to access the production environment.
     */
    PENDING,

    /** This is an active registrar account which is allowed to provision and modify domains. */
    ACTIVE,

    /**
     * This is a suspended account which is disallowed from provisioning new domains, but can
     * otherwise still perform other operations to continue operations.
     */
    SUSPENDED;
  }

  /** Method for acquiring money from a registrar customer. */
  public enum BillingMethod {

    /** Billing method where billing invoice data is exported to an external accounting system. */
    EXTERNAL,

    /** Billing method where we accept Braintree credit card payments in the Registrar Console. */
    BRAINTREE;
  }

  private static final RegistryEnvironment ENVIRONMENT = RegistryEnvironment.get();

  /** Regex for E.164 phone number format specified by {@code contact.xsd}. */
  private static final Pattern E164_PATTERN = Pattern.compile("\\+[0-9]{1,3}\\.[0-9]{1,14}");

  /** Regex for telephone support passcode (5 digit string). */
  public static final Pattern PHONE_PASSCODE_PATTERN = Pattern.compile("\\d{5}");

  /** The states in which a {@link Registrar} is considered {@link #isActive active}. */
  private static final ImmutableSet<State> ACTIVE_STATES =
      Sets.immutableEnumSet(State.ACTIVE, State.SUSPENDED);

  /**
   * The types for which a {@link Registrar} should be included in WHOIS and RDAP output. We exclude
   * registrars of type TEST. We considered excluding INTERNAL as well, but decided that
   * troubleshooting would be easier with INTERNAL registrars visible.
   */
  //TODO(b/27274151): Expand documentation of this field.
  private static final ImmutableSet<Type> PUBLICLY_VISIBLE_TYPES =
      immutableEnumSet(
          Type.REAL, Type.PDT, Type.OTE, Type.EXTERNAL_MONITORING, Type.MONITORING, Type.INTERNAL);

  @Parent
  Key<EntityGroupRoot> parent = getCrossTldKey();

  /**
   * Unique registrar client id. Must conform to "clIDType" as defined in RFC5730.
   * @see "http://tools.ietf.org/html/rfc5730#section-4.2"
   */
  @Id
  String clientIdentifier;

  /**
   * Registrar name. This is a distinct from the client identifier since there are no restrictions
   * on its length.
   *
   * <p>NB: We are assuming that this field is unique across all registrar entities. This is not
   * formally enforced in our datastore, but should be enforced by ICANN in that no two registrars
   * will be accredited with the same name.
   *
   * @see "http://www.icann.org/registrar-reports/accredited-list.html"
   */
  @Index
  String registrarName;

  /** The type of this registrar. */
  Type type;

  /** The state of this registrar. */
  State state;

  /** The set of TLDs which this registrar is allowed to access. */
  Set<String> allowedTlds;

  /** Host name of WHOIS server. */
  String whoisServer;

  /**
   * Whether registration of premium names should be blocked over EPP. If this is set to true, then
   * the only way to register premium names is with the superuser flag.
   */
  boolean blockPremiumNames;

  // Authentication.

  /** X.509 PEM client certificate(s) used to authenticate registrar to EPP service. */
  String clientCertificate;

  /** Base64 encoded SHA256 hash of {@link #clientCertificate}. */
  String clientCertificateHash;

  /**
   * Optional secondary X.509 PEM certificate to try if {@link #clientCertificate} does not work.
   *
   * <p>This allows registrars to migrate certificates without downtime.
   */
  String failoverClientCertificate;

  /** Base64 encoded SHA256 hash of {@link #failoverClientCertificate}. */
  String failoverClientCertificateHash;

  /** A whitelist of netmasks (in CIDR notation) which the client is allowed to connect from. */
  List<CidrAddressBlock> ipAddressWhitelist;

  /** A hashed password for EPP access. The hash is a base64 encoded SHA256 string. */
  String passwordHash;

  /** Randomly generated hash salt. */
  String salt;

  // The following fields may appear redundant to the above, but are
  // implied by RFC examples and should be interpreted as "for the
  // Registrar as a whole".
  /**
   * Localized {@link RegistrarAddress} for this registrar. Contents can be represented in
   * unrestricted UTF-8.
   */
  @IgnoreSave(IfNull.class)
  RegistrarAddress localizedAddress;

  /**
   * Internationalized {@link RegistrarAddress} for this registrar. All contained values must be
   * representable in the 7-bit US-ASCII character set.
   */
  @IgnoreSave(IfNull.class)
  RegistrarAddress internationalizedAddress;

  /** Voice number. */
  String phoneNumber;

  /** Fax number. */
  String faxNumber;

  /** Email address. */
  String emailAddress;

  // External IDs.

  /**
   * Registrar identifier used for reporting to ICANN.
   * <ul>
   *   <li>8 is used for Testing Registrar.
   *   <li>9997 is used by ICAAN for SLA monitoring.
   *   <li>9999 is used for cases when the registry operator acts as registrar.
   * </ul>
   * @see "http://www.iana.org/assignments/registrar-ids/registrar-ids.txt"
   */
  @Index
  Long ianaIdentifier;

  /** Identifier of registrar used in external billing system (e.g. Oracle). */
  Long billingIdentifier;

  /** URL of registrar's website. */
  String url;

  /** Referral URL of registrar. */
  String referralUrl;

  /**
   * ICANN referral email address.
   *
   * <p>This value is specified in the initial registrar contact. It can't be edited in the web GUI
   * and it must be specified when the registrar account is created.
   */
  String icannReferralEmail;

  /** Id of the folder in drive used to publish information for this registrar. */
  String driveFolderId;

  // Metadata.

  /** The time when this registrar was created. */
  CreateAutoTimestamp creationTime = CreateAutoTimestamp.create(null);

  /** An automatically managed last-saved timestamp. */
  UpdateAutoTimestamp lastUpdateTime = UpdateAutoTimestamp.create(null);

  /**
   * The time that the certificate was last updated.
   */
  DateTime lastCertificateUpdateTime;

  /**
   * Telephone support passcode (5-digit numeric)
   */
  String phonePasscode;

  /**
   * A dirty bit for whether RegistrarContact changes have been made that haven't been synced to
   * Google Groups yet. When creating a new instance, contacts require syncing by default.
   */
  boolean contactsRequireSyncing = true;

  /**
   * Method for receiving money from a registrar customer.
   *
   * <p>Each registrar may opt-in to their preferred billing method. This value can be changed at
   * any time using the {@code update_registrar} command.
   *
   * <p><b>Note:</b> This value should not be changed if the balance is non-zero.
   */
  BillingMethod billingMethod;

  @NonFinalForTesting
  private static Supplier<byte[]> saltSupplier = new Supplier<byte[]>() {
    @Override
    public byte[] get() {
      // There are 32 bytes in a sha-256 hash, and the salt should generally be the same size.
      byte[] salt = new byte[32];
      new SecureRandom().nextBytes(salt);
      return salt;
    }};

  public String getClientIdentifier() {
    return clientIdentifier;
  }

  public DateTime getCreationTime() {
    return creationTime.getTimestamp();
  }

  public Long getIanaIdentifier() {
    return ianaIdentifier;
  }

  public Long getBillingIdentifier() {
    return billingIdentifier;
  }

  public DateTime getLastUpdateTime() {
    return lastUpdateTime.getTimestamp();
  }

  public DateTime getLastCertificateUpdateTime() {
    return lastCertificateUpdateTime;
  }

  public String getRegistrarName() {
    return registrarName;
  }

  public Type getType() {
    return type;
  }

  public State getState() {
    return state;
  }

  public ImmutableSortedSet<String> getAllowedTlds() {
    return nullToEmptyImmutableSortedCopy(allowedTlds);
  }

  /** Returns {@code true} if registrar is active. */
  public boolean isActive() {
    return ACTIVE_STATES.contains(state);
  }

  /** Returns {@code true} if registrar should be visible in WHOIS results. */
  public boolean isActiveAndPubliclyVisible() {
    return ACTIVE_STATES.contains(state) && PUBLICLY_VISIBLE_TYPES.contains(type);
  }

  public String getClientCertificate() {
    return clientCertificate;
  }

  public String getClientCertificateHash() {
    return clientCertificateHash;
  }

  public String getFailoverClientCertificate() {
    return failoverClientCertificate;
  }

  public String getFailoverClientCertificateHash() {
    return failoverClientCertificateHash;
  }

  public ImmutableList<CidrAddressBlock> getIpAddressWhitelist() {
    return nullToEmptyImmutableCopy(ipAddressWhitelist);
  }

  public RegistrarAddress getLocalizedAddress() {
    return localizedAddress;
  }

  public RegistrarAddress getInternationalizedAddress() {
    return internationalizedAddress;
  }

  public String getPhoneNumber() {
    return phoneNumber;
  }

  public String getFaxNumber() {
    return faxNumber;
  }

  public String getEmailAddress() {
    return emailAddress;
  }

  public String getWhoisServer() {
    if (whoisServer == null) {
      return ENVIRONMENT.config().getRegistrarDefaultWhoisServer();
    }
    return whoisServer;
  }

  public boolean getBlockPremiumNames() {
    return blockPremiumNames;
  }

  public boolean getContactsRequireSyncing() {
    return contactsRequireSyncing;
  }

  public String getUrl() {
    return url;
  }

  public String getReferralUrl() {
    if (referralUrl == null) {
      return ENVIRONMENT.config().getRegistrarDefaultReferralUrl().toString();
    }
    return referralUrl;
  }

  public String getIcannReferralEmail() {
    return nullToEmpty(icannReferralEmail);
  }

  public String getDriveFolderId() {
    return driveFolderId;
  }

  public BillingMethod getBillingMethod() {
    return firstNonNull(billingMethod, BillingMethod.EXTERNAL);
  }

  /**
   * Returns a list of all {@link RegistrarContact} objects for this registrar sorted by their email
   * address.
   */
  public ImmutableSortedSet<RegistrarContact> getContacts() {
    return FluentIterable
        .from(ofy().load().type(RegistrarContact.class).ancestor(Registrar.this))
        .filter(notNull())
        .toSortedSet(new Comparator<RegistrarContact>() {
          @Override
          public int compare(RegistrarContact rc1, RegistrarContact rc2) {
            return rc1.getEmailAddress().compareTo(rc2.getEmailAddress());
          }});
  }

  @Override
  public Map<String, Object> toJsonMap() {
    return new JsonMapBuilder()
        .put("clientIdentifier", clientIdentifier)
        .put("ianaIdentifier", ianaIdentifier)
        .put("billingIdentifier", billingIdentifier)
        .putString("creationTime", creationTime.getTimestamp())
        .putString("lastUpdateTime", lastUpdateTime.getTimestamp())
        .putString("lastCertificateUpdateTime", lastCertificateUpdateTime)
        .put("registrarName", registrarName)
        .put("type", type)
        .put("state", state)
        .put("clientCertificate", clientCertificate)
        .put("clientCertificateHash", clientCertificateHash)
        .put("failoverClientCertificate", failoverClientCertificate)
        .put("failoverClientCertificateHash", failoverClientCertificateHash)
        .put("localizedAddress", localizedAddress)
        .put("internationalizedAddress", internationalizedAddress)
        .put("phoneNumber", phoneNumber)
        .put("faxNumber", faxNumber)
        .put("emailAddress", emailAddress)
        .put("whoisServer", getWhoisServer())
        .put("blockPremiumNames", blockPremiumNames)
        .put("url", url)
        .put("referralUrl", getReferralUrl())
        .put("icannReferralEmail", getIcannReferralEmail())
        .put("driveFolderId", driveFolderId)
        .put("phoneNumber", phoneNumber)
        .put("phonePasscode", phonePasscode)
        .putListOfStrings("allowedTlds", getAllowedTlds())
        .putListOfStrings("ipAddressWhitelist", ipAddressWhitelist)
        .putListOfJsonObjects("contacts", getContacts())
        .build();
  }

  private String hashPassword(String password) {
    try {
      return base64().encode(
          MessageDigest.getInstance("SHA-256").digest(
              (password + salt).getBytes(UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      // All implementations of MessageDigest are required to support SHA-256.
      throw new RuntimeException(e);
    }
  }

  private static String checkValidPhoneNumber(String phoneNumber) {
    checkArgument(
        E164_PATTERN.matcher(phoneNumber).matches(),
        "Not a valid E.164 phone number: %s", phoneNumber);
    return phoneNumber;
  }

  public boolean testPassword(String password) {
    return hashPassword(password).equals(passwordHash);
  }

  public String getPhonePasscode() {
    return phonePasscode;
  }

  @Override
  public Builder asBuilder() {
    return new Builder(clone(this));
  }

  /** A builder for constructing {@link Registrar}, since it is immutable. */
  public static class Builder extends Buildable.Builder<Registrar> {
    public Builder() {}

    private Builder(Registrar instance) {
      super(instance);
    }

    public Builder setClientIdentifier(String clientIdentifier) {
      // Client id must be [3,16] chars long. See "clIDType" in the base EPP schema of RFC 5730.
      // (Need to validate this here as there's no matching EPP XSD for validation.)
      checkArgument(clientIdentifier.length() >= 3 && clientIdentifier.length() <= 16,
          "Client identifier must be 3-16 characters long.");
      getInstance().clientIdentifier = clientIdentifier;
      return this;
    }

    public Builder setIanaIdentifier(Long ianaIdentifier) {
      checkArgument(ianaIdentifier == null || ianaIdentifier > 0,
          "IANA ID must be a positive number");
      getInstance().ianaIdentifier = ianaIdentifier;
      return this;
    }

    public Builder setBillingIdentifier(Long billingIdentifier) {
      checkArgument(billingIdentifier == null || billingIdentifier > 0,
          "Billing ID must be a positive number");
      getInstance().billingIdentifier = billingIdentifier;
      return this;
    }

    public Builder setRegistrarName(String registrarName) {
      getInstance().registrarName = registrarName;
      return this;
    }

    public Builder setType(Type type) {
      getInstance().type = type;
      return this;
    }

    public Builder setState(State state) {
      getInstance().state = state;
      return this;
    }

    public Builder setAllowedTlds(Set<String> allowedTlds) {
      for (String tld : allowedTlds) {
        assertTldExists(tld);
      }
      getInstance().allowedTlds = ImmutableSortedSet.copyOf(allowedTlds);
      return this;
    }

    public Builder setClientCertificate(String clientCertificate, DateTime now) {
      clientCertificate = emptyToNull(clientCertificate);
      String clientCertificateHash = calculateHash(clientCertificate);
      if (!Objects.equals(clientCertificate, getInstance().clientCertificate)
          || !Objects.equals(clientCertificateHash, getInstance().clientCertificateHash)) {
        getInstance().clientCertificate = clientCertificate;
        getInstance().clientCertificateHash = clientCertificateHash;
        getInstance().lastCertificateUpdateTime = now;
      }
      return this;
    }

    public Builder setFailoverClientCertificate(String clientCertificate, DateTime now) {
      clientCertificate = emptyToNull(clientCertificate);
      String clientCertificateHash = calculateHash(clientCertificate);
      if (!Objects.equals(clientCertificate, getInstance().failoverClientCertificate)
          || !Objects.equals(clientCertificateHash, getInstance().failoverClientCertificateHash)) {
        getInstance().failoverClientCertificate = clientCertificate;
        getInstance().failoverClientCertificateHash = clientCertificateHash;
        getInstance().lastCertificateUpdateTime = now;
      }
      return this;
    }

    private static String calculateHash(String clientCertificate) {
      if (clientCertificate == null) {
        return null;
      }
      try {
        return getCertificateHash(loadCertificate(clientCertificate));
      } catch (CertificateParsingException e) {
        throw new IllegalArgumentException(e);
      }
    }

    /**
     * Sets client certificate hash, but not the certificate.
     *
     * <p><b>Warning:</b> {@link #setClientCertificate(String, DateTime)} sets the hash for you and
     * is preferred. Calling this method will nullify the {@code clientCertificate} field.
     */
    public Builder setClientCertificateHash(String clientCertificateHash) {
      if (clientCertificateHash != null) {
        checkArgument(Pattern.matches("[A-Za-z0-9+/]+", clientCertificateHash),
            "--cert_hash not a valid base64 (no padding) value");
        checkArgument(base64().decode(clientCertificateHash).length == 256 / 8,
            "--cert_hash base64 does not decode to 256 bits");
      }
      getInstance().clientCertificate = null;
      getInstance().clientCertificateHash = clientCertificateHash;
      return this;
    }

    public Builder setContactsRequireSyncing(boolean contactsRequireSyncing) {
      getInstance().contactsRequireSyncing = contactsRequireSyncing;
      return this;
    }

    public Builder setIpAddressWhitelist(Iterable<CidrAddressBlock> ipAddressWhitelist) {
      getInstance().ipAddressWhitelist = ImmutableList.copyOf(ipAddressWhitelist);
      return this;
    }

    public Builder setLocalizedAddress(RegistrarAddress localizedAddress) {
      getInstance().localizedAddress = localizedAddress;
      return this;
    }

    public Builder setInternationalizedAddress(RegistrarAddress internationalizedAddress) {
      getInstance().internationalizedAddress = internationalizedAddress;
      return this;
    }

    public Builder setPhoneNumber(String phoneNumber) {
      getInstance().phoneNumber = (phoneNumber == null)
          ? null
          : checkValidPhoneNumber(phoneNumber);
      return this;
    }

    public Builder setFaxNumber(String faxNumber) {
      getInstance().faxNumber = (faxNumber == null)
          ? null
          : checkValidPhoneNumber(faxNumber);
      return this;
    }

    public Builder setEmailAddress(String emailAddress) {
      getInstance().emailAddress = emailAddress;
      return this;
    }

    public Builder setWhoisServer(String whoisServer) {
      getInstance().whoisServer = whoisServer;
      return this;
    }

    public Builder setBlockPremiumNames(boolean blockPremiumNames) {
      getInstance().blockPremiumNames = blockPremiumNames;
      return this;
    }

    public Builder setUrl(String url) {
      getInstance().url = url;
      return this;
    }

    public Builder setReferralUrl(String referralUrl) {
      getInstance().referralUrl = referralUrl;
      return this;
    }

    public Builder setIcannReferralEmail(String icannReferralEmail) {
      getInstance().icannReferralEmail = icannReferralEmail;
      return this;
    }

    public Builder setDriveFolderId(String driveFolderId) {
      getInstance().driveFolderId = driveFolderId;
      return this;
    }

    public Builder setBillingMethod(BillingMethod billingMethod) {
      getInstance().billingMethod = billingMethod;
      return this;
    }

    public Builder setPassword(String password) {
      // Passwords must be [6,16] chars long. See "pwType" in the base EPP schema of RFC 5730.
      checkArgument(password != null && password.length() >= 6 && password.length() <= 16,
          "Password must be [6,16] characters long.");
      getInstance().salt = base64().encode(saltSupplier.get());
      getInstance().passwordHash = getInstance().hashPassword(password);
      return this;
    }

    /** @throws IllegalArgumentException if provided passcode is not 5-digit numeric */
    public Builder setPhonePasscode(String phonePasscode) {
      checkArgument(phonePasscode == null
          || PHONE_PASSCODE_PATTERN.matcher(phonePasscode).matches(),
          "Not a valid telephone passcode (must be 5 digits long): %s", phonePasscode);
      getInstance().phonePasscode = phonePasscode;
      return this;
    }

    /** Build the registrar, nullifying empty fields. */
    @Override
    public Registrar build() {
      checkNotNull(getInstance().type, "Registrar type cannot be null");
      checkArgument(getInstance().type.isValidIanaId(getInstance().ianaIdentifier),
          String.format("Supplied IANA ID is not valid for %s registrar type: %s",
            getInstance().type, getInstance().ianaIdentifier));
      return cloneEmptyToNull(super.build());
    }
  }

  /** Load a registrar entity by its client id outside of a transaction. */
  @Nullable
  public static Registrar loadByClientId(final String clientId) {
    return ofy().doTransactionless(new Work<Registrar>() {
      @Override
      public Registrar run() {
        return ofy().load()
            .type(Registrar.class)
            .parent(getCrossTldKey())
            .id(clientId)
            .now();
      }});
  }

  /**
   * Load registrar entities by client id range outside of a transaction.
   *
   * @param clientIdStart returned registrars will have a client id greater than or equal to this
   * @param clientIdAfterEnd returned registrars will have a client id less than this
   * @param resultSetMaxSize the maximum number of registrar entities to be returned
   */
  public static Iterable<Registrar> loadByClientIdRange(
      final String clientIdStart, final String clientIdAfterEnd, final int resultSetMaxSize) {
    return ofy().doTransactionless(new Work<Iterable<Registrar>>() {
      @Override
      public Iterable<Registrar> run() {
        return ofy().load()
            .type(Registrar.class)
            .filterKey(">=", Key.create(getCrossTldKey(), Registrar.class, clientIdStart))
            .filterKey("<", Key.create(getCrossTldKey(), Registrar.class, clientIdAfterEnd))
            .limit(resultSetMaxSize);
      }});
  }

  /** Load a registrar entity by its name outside of a transaction. */
  @Nullable
  public static Registrar loadByName(final String name) {
    return ofy().doTransactionless(new Work<Registrar>() {
      @Override
      public Registrar run() {
        return ofy().load()
            .type(Registrar.class)
            .filter("registrarName", name)
            .first()
            .now();
      }});
  }

  /**
   * Load registrar entities by registrar name range, inclusive of the start but not the end,
   * outside of a transaction.
   *
   * @param nameStart returned registrars will have a name greater than or equal to this
   * @param nameAfterEnd returned registrars will have a name less than this
   * @param resultSetMaxSize the maximum number of registrar entities to be returned
   */
  public static Iterable<Registrar> loadByNameRange(
      final String nameStart, final String nameAfterEnd, final int resultSetMaxSize) {
    return ofy().doTransactionless(new Work<Iterable<Registrar>>() {
      @Override
      public Iterable<Registrar> run() {
        return ofy().load()
            .type(Registrar.class)
            .filter("registrarName >=", nameStart)
            .filter("registrarName <", nameAfterEnd)
            .limit(resultSetMaxSize);
      }});
  }

  /**
   * Load registrar entities by IANA identifier range outside of a transaction.
   *
   * @param ianaIdentifierStart returned registrars will have an IANA id greater than or equal to
   *        this
   * @param ianaIdentifierAfterEnd returned registrars will have an IANA id less than this
   * @param resultSetMaxSize the maximum number of registrar entities to be returned
   */
  public static Iterable<Registrar> loadByIanaIdentifierRange(
      final Long ianaIdentifierStart,
      final Long ianaIdentifierAfterEnd,
      final int resultSetMaxSize) {
    return ofy().doTransactionless(new Work<Iterable<Registrar>>() {
      @Override
      public Iterable<Registrar> run() {
        return ofy().load()
            .type(Registrar.class)
            .filter("ianaIdentifier >=", ianaIdentifierStart)
            .filter("ianaIdentifier <", ianaIdentifierAfterEnd)
            .limit(resultSetMaxSize);
      }});
  }

  /** Loads all registrar entities. */
  public static Iterable<Registrar> loadAll() {
    return ofy().load().type(Registrar.class).ancestor(getCrossTldKey());
  }

  /** Loads all active registrar entities. */
  public static FluentIterable<Registrar> loadAllActive() {
    return FluentIterable.from(loadAll()).filter(IS_ACTIVE);
  }

  private static final Predicate<Registrar> IS_ACTIVE = new Predicate<Registrar>() {
    @Override
    public boolean apply(Registrar registrar) {
      return registrar.isActive();
    }};

  /** Loads all active registrar entities. */
  public static FluentIterable<Registrar> loadAllActiveAndPubliclyVisible() {
    return FluentIterable.from(loadAll()).filter(IS_ACTIVE_AND_PUBLICLY_VISIBLE);
  }

  private static final Predicate<Registrar> IS_ACTIVE_AND_PUBLICLY_VISIBLE =
      new Predicate<Registrar>() {
        @Override
        public boolean apply(Registrar registrar) {
          return registrar.isActiveAndPubliclyVisible();
        }};
}
