// Copyright 2024 The Nomulus Authors. All Rights Reserved.
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
import static com.google.common.io.BaseEncoding.base64;
import static google.registry.model.registrar.Registrar.checkValidEmail;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.util.CollectionUtils.nullToEmptyImmutableSortedCopy;
import static google.registry.util.PasswordUtils.SALT_SUPPLIER;
import static google.registry.util.PasswordUtils.hashPassword;
import static java.util.stream.Collectors.joining;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.gson.annotations.Expose;
import google.registry.model.Buildable.GenericBuilder;
import google.registry.model.ImmutableObject;
import google.registry.model.JsonMapBuilder;
import google.registry.model.Jsonifiable;
import google.registry.model.UnsafeSerializable;
import google.registry.persistence.VKey;
import google.registry.util.PasswordUtils;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import java.io.Serializable;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * A contact for a Registrar. Note, equality, hashCode and comparable have been overridden to only
 * enable key equality.
 *
 * <p>IMPORTANT NOTE: Any time that you change, update, or delete RegistrarContact entities, you
 * *MUST* also modify the persisted Registrar entity with {@link Registrar#contactsRequireSyncing}
 * set to true.
 */
@Entity
@IdClass(RegistrarPoc.RegistrarPocId.class)
public class RegistrarPoc extends ImmutableObject implements Jsonifiable, UnsafeSerializable {
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
      displayName = display;
      this.required = required;
    }
  }

  @Expose
  @Column(name = "id", insertable = false, updatable = false)
  protected Long id;

  /** The name of the contact. */
  @Expose String name;

  /** The contact email address of the contact. */
  @Id @Expose String emailAddress;

  @Id @Expose public String registrarId;

  /** External email address of this contact used for registry lock confirmations. */
  String registryLockEmailAddress;

  /** The voice number of the contact. */
  @Expose String phoneNumber;

  /** The fax number of the contact. */
  @Expose String faxNumber;

  /**
   * Multiple types are used to associate the registrar contact with various mailing groups. This
   * data is internal to the registry.
   */
  @Enumerated(EnumType.STRING)
  @Expose
  Set<Type> types;

  /**
   * Whether this contact is publicly visible in WHOIS registrar query results as an Admin contact.
   */
  @Column(nullable = false)
  @Expose
  boolean visibleInWhoisAsAdmin = false;

  /**
   * Whether this contact is publicly visible in WHOIS registrar query results as a Technical
   * contact.
   */
  @Column(nullable = false)
  @Expose
  boolean visibleInWhoisAsTech = false;

  /**
   * Whether this contact's phone number and email address is publicly visible in WHOIS domain query
   * results as registrar abuse contact info.
   */
  @Column(nullable = false)
  @Expose
  boolean visibleInDomainWhoisAsAbuse = false;

  /**
   * Whether the contact is allowed to set their registry lock password through the registrar
   * console. This will be set to false on contact creation and when the user sets a password.
   */
  @Column(nullable = false)
  boolean allowedToSetRegistryLockPassword = false;

  /**
   * A hashed password that exists iff this contact is registry-lock-enabled. The hash is a base64
   * encoded SHA256 string.
   */
  String registryLockPasswordHash;

  /** Randomly generated hash salt. */
  String registryLockPasswordSalt;

  /**
   * Helper to update the contacts associated with a Registrar. This requires querying for the
   * existing contacts, deleting existing contacts that are not part of the given {@code contacts}
   * set, and then saving the given {@code contacts}.
   *
   * <p>IMPORTANT NOTE: If you call this method then it is your responsibility to also persist the
   * relevant Registrar entity with the {@link Registrar#contactsRequireSyncing} field set to true.
   */
  public static void updateContacts(
      final Registrar registrar, final ImmutableSet<RegistrarPoc> contacts) {
    ImmutableSet<String> emailAddressesToKeep =
        contacts.stream().map(RegistrarPoc::getEmailAddress).collect(toImmutableSet());
    tm().query(
            "DELETE FROM RegistrarPoc WHERE registrarId = :registrarId AND "
                + "emailAddress NOT IN :emailAddressesToKeep")
        .setParameter("registrarId", registrar.getRegistrarId())
        .setParameter("emailAddressesToKeep", emailAddressesToKeep)
        .executeUpdate();

    tm().putAll(contacts);
  }

  public Long getId() {
    return id;
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

  public Builder<? extends RegistrarPoc, ?> asBuilder() {
    return new Builder<>(clone(this));
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
    return PasswordUtils.verifyPassword(
        registryLockPassword, registryLockPasswordHash, registryLockPasswordSalt);
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
   * Registrar-Console access: Yes
   * Login Email Address: person@registry.example
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
        .put("id", getId())
        .build();
  }

  @Override
  public VKey<RegistrarPoc> createVKey() {
    return VKey.create(RegistrarPoc.class, new RegistrarPocId(emailAddress, registrarId));
  }

  /**
   * These methods set the email address and registrar ID
   *
   * <p>This should only be used for restoring the fields of an object being loaded in a PostLoad
   * method (effectively, when it is still under construction by Hibernate). In all other cases, the
   * object should be regarded as immutable and changes should go through a Builder.
   *
   * <p>In addition to this special case use, this method must exist to satisfy Hibernate.
   */
  public void setEmailAddress(String emailAddress) {
    this.emailAddress = emailAddress;
  }

  public void setRegistrarId(String registrarId) {
    this.registrarId = registrarId;
  }

  /** A builder for constructing a {@link RegistrarPoc}, since it is immutable. */
  public static class Builder<T extends RegistrarPoc, B extends Builder<T, B>>
      extends GenericBuilder<T, B> {
    public Builder() {}

    protected Builder(T instance) {
      super(instance);
    }

    /** Build the registrar, nullifying empty fields. */
    @Override
    public T build() {
      checkNotNull(getInstance().registrarId, "Registrar ID cannot be null");
      checkValidEmail(getInstance().emailAddress);
      // Check allowedToSetRegistryLockPassword here because if we want to allow the user to set
      // a registry lock password, we must also set up the correct registry lock email concurrently
      // or beforehand.
      if (getInstance().allowedToSetRegistryLockPassword) {
        checkArgument(
            !isNullOrEmpty(getInstance().registryLockEmailAddress),
            "Registry lock email must not be null if allowing registry lock access");
      }
      return cloneEmptyToNull(super.build());
    }

    public B setName(String name) {
      getInstance().name = name;
      return thisCastToDerived();
    }

    public B setEmailAddress(String emailAddress) {
      getInstance().emailAddress = emailAddress;
      return thisCastToDerived();
    }

    public B setRegistryLockEmailAddress(@Nullable String registryLockEmailAddress) {
      getInstance().registryLockEmailAddress = registryLockEmailAddress;
      return thisCastToDerived();
    }

    public B setPhoneNumber(String phoneNumber) {
      getInstance().phoneNumber = phoneNumber;
      return thisCastToDerived();
    }

    public B setRegistrarId(String registrarId) {
      getInstance().registrarId = registrarId;
      return thisCastToDerived();
    }

    public B setRegistrar(Registrar registrar) {
      getInstance().registrarId = registrar.getRegistrarId();
      return thisCastToDerived();
    }

    public B setFaxNumber(String faxNumber) {
      getInstance().faxNumber = faxNumber;
      return thisCastToDerived();
    }

    public B setTypes(Iterable<Type> types) {
      getInstance().types = ImmutableSet.copyOf(types);
      return thisCastToDerived();
    }

    public B setVisibleInWhoisAsAdmin(boolean visible) {
      getInstance().visibleInWhoisAsAdmin = visible;
      return thisCastToDerived();
    }

    public B setVisibleInWhoisAsTech(boolean visible) {
      getInstance().visibleInWhoisAsTech = visible;
      return thisCastToDerived();
    }

    public B setVisibleInDomainWhoisAsAbuse(boolean visible) {
      getInstance().visibleInDomainWhoisAsAbuse = visible;
      return thisCastToDerived();
    }

    public B setAllowedToSetRegistryLockPassword(boolean allowedToSetRegistryLockPassword) {
      if (allowedToSetRegistryLockPassword) {
        getInstance().registryLockPasswordSalt = null;
        getInstance().registryLockPasswordHash = null;
      }
      getInstance().allowedToSetRegistryLockPassword = allowedToSetRegistryLockPassword;
      return thisCastToDerived();
    }

    public B setRegistryLockPassword(String registryLockPassword) {
      checkArgument(
          getInstance().allowedToSetRegistryLockPassword,
          "Not allowed to set registry lock password for this contact");
      checkArgument(
          !isNullOrEmpty(registryLockPassword), "Registry lock password was null or empty");
      byte[] salt = SALT_SUPPLIER.get();
      getInstance().registryLockPasswordSalt = base64().encode(salt);
      getInstance().registryLockPasswordHash = hashPassword(registryLockPassword, salt);
      getInstance().allowedToSetRegistryLockPassword = false;
      return thisCastToDerived();
    }
  }

  /** Class to represent the composite primary key for {@link RegistrarPoc} entity. */
  @VisibleForTesting
  public static class RegistrarPocId extends ImmutableObject implements Serializable {

    String emailAddress;

    String registrarId;

    // Hibernate requires this default constructor.
    @SuppressWarnings("unused")
    private RegistrarPocId() {}

    @VisibleForTesting
    public RegistrarPocId(String emailAddress, String registrarId) {
      this.emailAddress = emailAddress;
      this.registrarId = registrarId;
    }

    @Id
    public String getEmailAddress() {
      return emailAddress;
    }

    @Id
    public String getRegistrarId() {
      return registrarId;
    }
  }
}
