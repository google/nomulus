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

package google.registry.model.registrar;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Strings.isNullOrEmpty;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static com.google.common.collect.Sets.difference;
import static com.google.common.io.BaseEncoding.base64;
import static google.registry.model.common.EntityGroupRoot.getCrossTldKey;
import static google.registry.model.ofy.ObjectifyService.auditedOfy;
import static google.registry.model.registrar.Registrar.checkValidEmail;
import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.util.CollectionUtils.nullToEmptyImmutableSortedCopy;
import static google.registry.util.PasswordUtils.SALT_SUPPLIER;
import static google.registry.util.PasswordUtils.hashPassword;
import static java.util.stream.Collectors.joining;

import com.google.common.base.Enums;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Streams;
import com.googlecode.objectify.Key;
import com.googlecode.objectify.annotation.Entity;
import com.googlecode.objectify.annotation.Id;
import com.googlecode.objectify.annotation.Ignore;
import com.googlecode.objectify.annotation.Index;
import com.googlecode.objectify.annotation.OnLoad;
import com.googlecode.objectify.annotation.Parent;
import google.registry.model.Buildable;
import google.registry.model.ImmutableObject;
import google.registry.model.JsonMapBuilder;
import google.registry.model.Jsonifiable;
import google.registry.model.annotations.InCrossTld;
import google.registry.model.annotations.ReportedOn;
import google.registry.model.registrar.RegistrarContact.RegistrarPocId;
import google.registry.persistence.VKey;
import google.registry.schema.replay.DatastoreAndSqlEntity;
import java.io.Serializable;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.annotation.Nullable;
import javax.persistence.IdClass;
import javax.persistence.PostLoad;
import javax.persistence.Table;
import javax.persistence.Transient;

/**
 * A contact for a Registrar. Note, equality, hashCode and comparable have been overridden to only
 * enable key equality.
 *
 * <p>IMPORTANT NOTE: Any time that you change, update, or delete RegistrarContact entities, you
 * *MUST* also modify the persisted Registrar entity with {@link Registrar#contactsRequireSyncing}
 * set to true.
 *
 * <p>TODO(b/177567432): Rename the class name to RegistrarPoc after database migration
 */
@ReportedOn
@Entity
@javax.persistence.Entity(name = "RegistrarPoc")
@Table(
    indexes = {
      @javax.persistence.Index(columnList = "gaeUserId", name = "registrarpoc_gae_user_id_idx")
    })
@IdClass(RegistrarPocId.class)
@InCrossTld
public class RegistrarContact extends ImmutableObject
    implements DatastoreAndSqlEntity, Jsonifiable {

  @Parent @Transient Key<Registrar> parent;

  /**
   * Registrar contacts types for partner communication tracking.
   *
   * <p><b>Note:</b> These types only matter to the registry. They are not meant to be used for
   * WHOIS or RDAP results.
   */
  public enum Type {
    ABUSE("abuse", true),
    ADMIN("primary", true),
    BILLING("billing", true),
    LEGAL("legal", true),
    MARKETING("marketing", false),
    TECH("technical", true),
    WHOIS("whois-inquiry", true);

    private final String displayName;

    private final boolean required;

    public String getDisplayName() {
      return displayName;
    }

    public boolean isRequired() {
      return required;
    }

    Type(String display, boolean required) {
      this.displayName = display;
      this.required = required;
    }
  }

  /** The name of the contact. */
  String name;

  /** The email address of the contact. */
  @Id @javax.persistence.Id String emailAddress;

  @Ignore @javax.persistence.Id String registrarId;

  /** External email address of this contact used for registry lock confirmations. */
  String registryLockEmailAddress;

  /** The voice number of the contact. */
  String phoneNumber;

  /** The fax number of the contact. */
  String faxNumber;

  /**
   * Multiple types are used to associate the registrar contact with various mailing groups. This
   * data is internal to the registry.
   */
  Set<Type> types;

  /**
   * A GAE user ID allowed to act as this registrar contact.
   *
   * <p>This can be derived from a known email address using http://email-to-gae-id.appspot.com.
   *
   * @see com.google.appengine.api.users.User#getUserId()
   */
  @Index String gaeUserId;

  /**
   * Whether this contact is publicly visible in WHOIS registrar query results as an Admin contact.
   */
  boolean visibleInWhoisAsAdmin = false;

  /**
   * Whether this contact is publicly visible in WHOIS registrar query results as a Technical
   * contact.
   */
  boolean visibleInWhoisAsTech = false;

  /**
   * Whether this contact's phone number and email address is publicly visible in WHOIS domain query
   * results as registrar abuse contact info.
   */
  boolean visibleInDomainWhoisAsAbuse = false;

  /**
   * Whether or not the contact is allowed to set their registry lock password through the registrar
   * console. This will be set to false on contact creation and when the user sets a password.
   */
  boolean allowedToSetRegistryLockPassword = false;

  /**
   * A hashed password that exists iff this contact is registry-lock-enabled. The hash is a base64
   * encoded SHA256 string.
   */
  String registryLockPasswordHash;

  /** Randomly generated hash salt. */
  String registryLockPasswordSalt;

  public static ImmutableSet<Type> typesFromCSV(String csv) {
    return typesFromStrings(Arrays.asList(csv.split(",")));
  }

  public static ImmutableSet<Type> typesFromStrings(Iterable<String> typeNames) {
    return Streams.stream(typeNames)
        .map(Enums.stringConverter(Type.class))
        .collect(toImmutableSet());
  }

  /**
   * Helper to update the contacts associated with a Registrar. This requires querying for the
   * existing contacts, deleting existing contacts that are not part of the given {@code contacts}
   * set, and then saving the given {@code contacts}.
   *
   * <p>IMPORTANT NOTE: If you call this method then it is your responsibility to also persist the
   * relevant Registrar entity with the {@link Registrar#contactsRequireSyncing} field set to true.
   */
  public static void updateContacts(
      final Registrar registrar, final ImmutableSet<RegistrarContact> contacts) {
    tm().transact(
            () -> {
              if (tm().isOfy()) {
                ImmutableSet<Key<RegistrarContact>> existingKeys =
                    ImmutableSet.copyOf(
                        auditedOfy()
                            .load()
                            .type(RegistrarContact.class)
                            .ancestor(registrar)
                            .keys());
                tm().delete(
                        difference(
                                existingKeys,
                                contacts.stream().map(Key::create).collect(toImmutableSet()))
                            .stream()
                            .map(key -> VKey.createOfy(RegistrarContact.class, key))
                            .collect(toImmutableSet()));
              } else {
                ImmutableSet<String> emailAddressesToKeep =
                    contacts.stream()
                        .map(RegistrarContact::getEmailAddress)
                        .collect(toImmutableSet());
                jpaTm()
                    .query(
                        "DELETE FROM RegistrarPoc WHERE registrarId = :registrarId AND "
                            + "emailAddress NOT IN :emailAddressesToKeep")
                    .setParameter("registrarId", registrar.getClientId())
                    .setParameter("emailAddressesToKeep", emailAddressesToKeep)
                    .executeUpdate();
              }
              tm().putAll(contacts);
            });
  }

  public Key<Registrar> getParent() {
    return parent;
  }

  public String getName() {
    return name;
  }

  public String getEmailAddress() {
    return emailAddress;
  }

  public Optional<String> getRegistryLockEmailAddress() {
    return Optional.ofNullable(registryLockEmailAddress);
  }

  public String getPhoneNumber() {
    return phoneNumber;
  }

  public String getFaxNumber() {
    return faxNumber;
  }

  public ImmutableSortedSet<Type> getTypes() {
    return nullToEmptyImmutableSortedCopy(types);
  }

  public boolean getVisibleInWhoisAsAdmin() {
    return visibleInWhoisAsAdmin;
  }

  public boolean getVisibleInWhoisAsTech() {
    return visibleInWhoisAsTech;
  }

  public boolean getVisibleInDomainWhoisAsAbuse() {
    return visibleInDomainWhoisAsAbuse;
  }

  public String getGaeUserId() {
    return gaeUserId;
  }

  public Builder asBuilder() {
    return new Builder(clone(this));
  }

  public boolean isAllowedToSetRegistryLockPassword() {
    return allowedToSetRegistryLockPassword;
  }

  public boolean isRegistryLockAllowed() {
    return !isNullOrEmpty(registryLockPasswordHash) && !isNullOrEmpty(registryLockPasswordSalt);
  }

  public boolean verifyRegistryLockPassword(String registryLockPassword) {
    if (isNullOrEmpty(registryLockPassword)
        || isNullOrEmpty(registryLockPasswordSalt)
        || isNullOrEmpty(registryLockPasswordHash)) {
      return false;
    }
    return hashPassword(registryLockPassword, registryLockPasswordSalt)
        .equals(registryLockPasswordHash);
  }

  /**
   * Returns a string representation that's human friendly.
   *
   * <p>The output will look something like this:
   *
   * <pre>{@code
   * Some Person
   * person@example.com
   * Tel: +1.2125650666
   * Types: [ADMIN, WHOIS]
   * Visible in WHOIS as Admin contact: Yes
   * Visible in WHOIS as Technical contact: No
   * GAE-UserID: 1234567890
   * Registrar-Console access: Yes
   * }</pre>
   */
  public String toStringMultilinePlainText() {
    StringBuilder result = new StringBuilder(256);
    result.append(getName()).append('\n');
    result.append(getEmailAddress()).append('\n');
    if (phoneNumber != null) {
      result.append("Tel: ").append(getPhoneNumber()).append('\n');
    }
    if (faxNumber != null) {
      result.append("Fax: ").append(getFaxNumber()).append('\n');
    }
    result.append("Types: ").append(getTypes()).append('\n');
    result
        .append("Visible in registrar WHOIS query as Admin contact: ")
        .append(getVisibleInWhoisAsAdmin() ? "Yes" : "No")
        .append('\n');
    result
        .append("Visible in registrar WHOIS query as Technical contact: ")
        .append(getVisibleInWhoisAsTech() ? "Yes" : "No")
        .append('\n');
    result
        .append(
            "Phone number and email visible in domain WHOIS query as "
                + "Registrar Abuse contact info: ")
        .append(getVisibleInDomainWhoisAsAbuse() ? "Yes" : "No")
        .append('\n');
    result
        .append("Registrar-Console access: ")
        .append(getGaeUserId() != null ? "Yes" : "No")
        .append('\n');
    if (getGaeUserId() != null) {
      result.append("GAE-UserID: ").append(getGaeUserId()).append('\n');
    }
    return result.toString();
  }

  @Override
  public Map<String, Object> toJsonMap() {
    return new JsonMapBuilder()
        .put("name", name)
        .put("emailAddress", emailAddress)
        .put("registryLockEmailAddress", registryLockEmailAddress)
        .put("phoneNumber", phoneNumber)
        .put("faxNumber", faxNumber)
        .put("types", getTypes().stream().map(Object::toString).collect(joining(",")))
        .put("visibleInWhoisAsAdmin", visibleInWhoisAsAdmin)
        .put("visibleInWhoisAsTech", visibleInWhoisAsTech)
        .put("visibleInDomainWhoisAsAbuse", visibleInDomainWhoisAsAbuse)
        .put("allowedToSetRegistryLockPassword", allowedToSetRegistryLockPassword)
        .put("registryLockAllowed", isRegistryLockAllowed())
        .put("gaeUserId", gaeUserId)
        .build();
  }

  /** Sets Cloud SQL specific fields when the entity is loaded from Datastore. */
  @OnLoad
  void onLoad() {
    registrarId = parent.getName();
  }

  /** Sets Datastore specific fields when the entity is loaded from Cloud SQL. */
  @PostLoad
  void postLoad() {
    parent = Key.create(getCrossTldKey(), Registrar.class, registrarId);
  }

  public VKey<RegistrarContact> createVKey() {
    return createVKey(Key.create(this));
  }

  /** Creates a {@link VKey} instance from a {@link Key} instance. */
  public static VKey<RegistrarContact> createVKey(Key<RegistrarContact> key) {
    Key<Registrar> parent = key.getParent();
    String registrarId = parent.getName();
    String emailAddress = key.getName();
    return VKey.create(RegistrarContact.class, new RegistrarPocId(emailAddress, registrarId), key);
  }

  /** Class to represent the composite primary key for {@link RegistrarContact} entity. */
  static class RegistrarPocId extends ImmutableObject implements Serializable {

    String emailAddress;

    String registrarId;

    // Hibernate requires this default constructor.
    private RegistrarPocId() {}

    RegistrarPocId(String emailAddress, String registrarId) {
      this.emailAddress = emailAddress;
      this.registrarId = registrarId;
    }
  }

  /** A builder for constructing a {@link RegistrarContact}, since it is immutable. */
  public static class Builder extends Buildable.Builder<RegistrarContact> {
    public Builder() {}

    private Builder(RegistrarContact instance) {
      super(instance);
    }

    public Builder setParent(Registrar parent) {
      return this.setParent(Key.create(parent));
    }

    public Builder setParent(Key<Registrar> parentKey) {
      getInstance().parent = parentKey;
      return this;
    }

    /** Build the registrar, nullifying empty fields. */
    @Override
    public RegistrarContact build() {
      checkNotNull(getInstance().parent, "Registrar parent cannot be null");
      checkValidEmail(getInstance().emailAddress);
      // Check allowedToSetRegistryLockPassword here because if we want to allow the user to set
      // a registry lock password, we must also set up the correct registry lock email concurrently
      // or beforehand.
      if (getInstance().allowedToSetRegistryLockPassword) {
        checkArgument(
            !isNullOrEmpty(getInstance().registryLockEmailAddress),
            "Registry lock email must not be null if allowing registry lock access");
      }
      getInstance().registrarId = getInstance().parent.getName();
      return cloneEmptyToNull(super.build());
    }

    public Builder setName(String name) {
      getInstance().name = name;
      return this;
    }

    public Builder setEmailAddress(String emailAddress) {
      getInstance().emailAddress = emailAddress;
      return this;
    }

    public Builder setRegistryLockEmailAddress(@Nullable String registryLockEmailAddress) {
      getInstance().registryLockEmailAddress = registryLockEmailAddress;
      return this;
    }

    public Builder setPhoneNumber(String phoneNumber) {
      getInstance().phoneNumber = phoneNumber;
      return this;
    }

    public Builder setFaxNumber(String faxNumber) {
      getInstance().faxNumber = faxNumber;
      return this;
    }

    public Builder setTypes(Iterable<Type> types) {
      getInstance().types = ImmutableSet.copyOf(types);
      return this;
    }

    public Builder setVisibleInWhoisAsAdmin(boolean visible) {
      getInstance().visibleInWhoisAsAdmin = visible;
      return this;
    }

    public Builder setVisibleInWhoisAsTech(boolean visible) {
      getInstance().visibleInWhoisAsTech = visible;
      return this;
    }

    public Builder setVisibleInDomainWhoisAsAbuse(boolean visible) {
      getInstance().visibleInDomainWhoisAsAbuse = visible;
      return this;
    }

    public Builder setGaeUserId(String gaeUserId) {
      getInstance().gaeUserId = gaeUserId;
      return this;
    }

    public Builder setAllowedToSetRegistryLockPassword(boolean allowedToSetRegistryLockPassword) {
      if (allowedToSetRegistryLockPassword) {
        getInstance().registryLockPasswordSalt = null;
        getInstance().registryLockPasswordHash = null;
      }
      getInstance().allowedToSetRegistryLockPassword = allowedToSetRegistryLockPassword;
      return this;
    }

    public Builder setRegistryLockPassword(String registryLockPassword) {
      checkArgument(
          getInstance().allowedToSetRegistryLockPassword,
          "Not allowed to set registry lock password for this contact");
      checkArgument(
          !isNullOrEmpty(registryLockPassword), "Registry lock password was null or empty");
      getInstance().registryLockPasswordSalt = base64().encode(SALT_SUPPLIER.get());
      getInstance().registryLockPasswordHash =
          hashPassword(registryLockPassword, getInstance().registryLockPasswordSalt);
      getInstance().allowedToSetRegistryLockPassword = false;
      return this;
    }
  }
}
