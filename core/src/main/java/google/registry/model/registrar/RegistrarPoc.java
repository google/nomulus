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

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static google.registry.model.registrar.Registrar.checkValidEmail;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.util.CollectionUtils.nullToEmptyImmutableSortedCopy;
import static java.util.stream.Collectors.joining;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.gson.annotations.Expose;
import google.registry.model.Buildable;
import google.registry.model.GetterDelegate;
import google.registry.model.ImmutableObject;
import google.registry.model.JsonMapBuilder;
import google.registry.model.Jsonifiable;
import google.registry.model.UnsafeSerializable;
import google.registry.persistence.VKey;
import google.registry.persistence.transaction.QueryComposer;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import java.io.Serializable;
import java.util.Map;
import java.util.Set;

/**
 * A contact for a Registrar. Note, equality, hashCode and comparable have been overridden to only
 * enable key equality.
 *
 * <p>IMPORTANT NOTE: Any time that you change, update, or delete RegistrarPoc entities, you *MUST*
 * also modify the persisted Registrar entity with {@link Registrar#contactsRequireSyncing} set to
 * true.
 */
@Entity
@IdClass(RegistrarPoc.RegistrarPocId.class)
public class RegistrarPoc extends ImmutableObject implements Jsonifiable, UnsafeSerializable {
  /**
   * Registrar contacts types for partner communication tracking.
   *
   * <p><b>Note:</b> These types only matter to the registry. They are not meant to be used for RDAP
   * results.
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
  @Column(insertable = false, updatable = false)
  protected Long id;

  /** The name of the contact. */
  @Expose String name;

  /** The contact email address of the contact. */
  @Id @Expose String emailAddress;

  @Id @Expose public String registrarId;

  /** The voice number of the contact. */
  @Expose String phoneNumber;

  /** The fax number of the contact. */
  @Expose String faxNumber;

  /**
   * Multiple types are used to associate the registrar contact with various mailing groups. This
   * data is internal to the registry.
   */
  @Enumerated(EnumType.STRING)
  @GetterDelegate(methodName = "getTypes")
  @Expose
  Set<Type> types;

  /** If this contact is publicly visible in RDAP registrar query results as an Admin contact */
  @Column(nullable = false, name = "visibleInWhoisAsAdmin")
  @Expose
  boolean visibleInRdapAsAdmin = false;

  /** If this contact is publicly visible in RDAP registrar query results as a Technical contact */
  @Column(nullable = false, name = "visibleInWhoisAsTech")
  @Expose
  boolean visibleInRdapAsTech = false;

  /**
   * If this contact's phone number and email address are publicly visible in RDAP domain query
   * results as registrar abuse contact info.
   */
  @Column(nullable = false, name = "visibleInDomainWhoisAsAbuse")
  @Expose
  boolean visibleInDomainRdapAsAbuse = false;

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

  public String getPhoneNumber() {
    return phoneNumber;
  }

  public String getFaxNumber() {
    return faxNumber;
  }

  public ImmutableSortedSet<Type> getTypes() {
    return nullToEmptyImmutableSortedCopy(types);
  }

  public boolean getVisibleInRdapAsAdmin() {
    return visibleInRdapAsAdmin;
  }

  public boolean getVisibleInRdapAsTech() {
    return visibleInRdapAsTech;
  }

  public boolean getVisibleInDomainRdapAsAbuse() {
    return visibleInDomainRdapAsAbuse;
  }

  public Builder asBuilder() {
    return new Builder(clone(this));
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
   * Visible in RDAP as Admin contact: Yes
   * Visible in RDAP as Technical contact: No
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
        .append("Visible in registrar RDAP query as Admin contact: ")
        .append(getVisibleInRdapAsAdmin() ? "Yes" : "No")
        .append('\n');
    result
        .append("Visible in registrar RDAP query as Technical contact: ")
        .append(getVisibleInRdapAsTech() ? "Yes" : "No")
        .append('\n');
    result
        .append(
            "Phone number and email visible in domain RDAP query as "
                + "Registrar Abuse contact info: ")
        .append(getVisibleInDomainRdapAsAbuse() ? "Yes" : "No")
        .append('\n');
    return result.toString();
  }

  @Override
  public Map<String, Object> toJsonMap() {
    return new JsonMapBuilder()
        .put("name", name)
        .put("emailAddress", emailAddress)
        .put("phoneNumber", phoneNumber)
        .put("faxNumber", faxNumber)
        .put("types", getTypes().stream().map(Object::toString).collect(joining(",")))
        .put("visibleInRdapAsAdmin", visibleInRdapAsAdmin)
        .put("visibleInRdapAsTech", visibleInRdapAsTech)
        .put("visibleInDomainRdapAsAbuse", visibleInDomainRdapAsAbuse)
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
  public static class Builder extends Buildable.Builder<RegistrarPoc> {
    public Builder() {}

    protected Builder(RegistrarPoc instance) {
      super(instance);
    }

    /** Build the registrar, nullifying empty fields. */
    @Override
    public RegistrarPoc build() {
      checkNotNull(getInstance().registrarId, "Registrar ID cannot be null");
      checkValidEmail(getInstance().emailAddress);
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

    public Builder setPhoneNumber(String phoneNumber) {
      getInstance().phoneNumber = phoneNumber;
      return this;
    }

    public Builder setRegistrarId(String registrarId) {
      getInstance().registrarId = registrarId;
      return this;
    }

    public Builder setRegistrar(Registrar registrar) {
      getInstance().registrarId = registrar.getRegistrarId();
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

    public Builder setVisibleInRdapAsAdmin(boolean visible) {
      getInstance().visibleInRdapAsAdmin = visible;
      return this;
    }

    public Builder setVisibleInRdapAsTech(boolean visible) {
      getInstance().visibleInRdapAsTech = visible;
      return this;
    }

    public Builder setVisibleInDomainRdapAsAbuse(boolean visible) {
      getInstance().visibleInDomainRdapAsAbuse = visible;
      return this;
    }
  }

  public static ImmutableList<RegistrarPoc> loadForRegistrar(String registrarId) {
    return tm().createQueryComposer(RegistrarPoc.class)
        .where("registrarId", QueryComposer.Comparator.EQ, registrarId)
        .list();
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
