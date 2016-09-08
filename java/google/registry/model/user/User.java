package google.registry.model.user;

import static com.google.common.base.Preconditions.checkArgument;
import static google.registry.model.ofy.ObjectifyService.ofy;
import static google.registry.model.ofy.Ofy.RECOMMENDED_MEMCACHE_EXPIRATION;

import com.google.common.base.Objects;
import com.googlecode.objectify.Key;
import com.googlecode.objectify.annotation.Cache;
import com.googlecode.objectify.annotation.Entity;
import com.googlecode.objectify.annotation.Id;
import com.googlecode.objectify.annotation.Parent;
import com.googlecode.objectify.annotation.Index;
import google.registry.model.Buildable;
import google.registry.model.CreateAutoTimestamp;
import google.registry.model.ImmutableObject;
import google.registry.model.JsonMapBuilder;
import google.registry.model.Jsonifiable;
import google.registry.model.UpdateAutoTimestamp;
import google.registry.model.common.GaeUserIdConverter;
import google.registry.model.registrar.Registrar;

import java.util.Map;

@Cache(expirationSeconds = RECOMMENDED_MEMCACHE_EXPIRATION)
@Entity
public class User extends ImmutableObject implements Jsonifiable, Buildable {

  @Parent
  Key<Registrar> parent;

  @Id
  long id;

  /** email address of an existing Google/Gmail account */
  @Index
  String email;

  /** A phrase used to authenticate the user by the administrator */
  String securityPhrase;

  /** The status of the user in the systems (Active/Inactive) Default value is Inactive */
  Boolean active = false;

  /** The user's first name */
  String firstName;

  /** The user's middle name */
  String middleName;

  /** The user's last name */
  String lastName;

  /** The user's phone number */
  String phoneNumber;

  /** The user's fax number */
  String faxNumber;

  /** The user's role */
  String role;

  /** google app engine user id */
  String gaeUserId;

  /** The time when this registrar was created. */
  CreateAutoTimestamp creationTime = CreateAutoTimestamp.create(null);

  /** An automatically managed last-saved timestamp. */
  UpdateAutoTimestamp lastUpdateTime = UpdateAutoTimestamp.create(null);

  public Key<Registrar> getParent() {
    return parent;
  }

  public String getEmail() {
    return email;
  }

  public String getSecurityPhrase() {
    return securityPhrase;
  }

  public Boolean getActive() {
    return active;
  }

  public String getFirstName() {
    return firstName;
  }

  public String getMiddleName() {
    return middleName;
  }

  public String getLastName() {
    return lastName;
  }

  public String getPhoneNumber() {
    return phoneNumber;
  }

  public String getFaxNumber() {
    return faxNumber;
  }

  public String getRole() {
    return role;
  }

  public String getGaeUserId() {
    return gaeUserId;
  }

  public long getId() {
    return id;
  }

  @Override
  public Map<String, Object> toJsonMap() {
    return new JsonMapBuilder()
        .put("email", email)
        .put("securityPhrase", securityPhrase)
        .put("isActive", active)
        .put("firstName", firstName)
        .put("middleName", middleName)
        .put("lastName", lastName)
        .put("phoneNumber", phoneNumber)
        .put("faxNumber", faxNumber)
        .put("role", role)
        .put("gaeUserId", gaeUserId)
        .put("clientId", getRegistrar().getClientIdentifier())
        .build();
  }

  public Registrar getRegistrar() {
    return ofy().load().key(parent).now();
  }

  @Override
  public User.Builder asBuilder() {
    return new User.Builder(clone(this));
  }

  @Override
  public boolean equals(Object o) {
    if (this == o)
      return true;
    if (o == null || getClass() != o.getClass())
      return false;
    if (!super.equals(o))
      return false;
    User user = (User) o;
    return id == user.id && Objects.equal(parent, user.parent) && Objects.equal(email, user.email)
               && Objects.equal(securityPhrase, user.securityPhrase) && Objects.equal(active,
        user.active) && Objects.equal(firstName, user.firstName) && Objects.equal(middleName,
        user.middleName) && Objects.equal(lastName, user.lastName) && Objects.equal(phoneNumber,
        user.phoneNumber) && Objects.equal(faxNumber, user.faxNumber) && Objects
                                                                             .equal(role, user.role)
               && Objects.equal(gaeUserId, user.gaeUserId);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(super.hashCode(), parent, id, email, securityPhrase, active, firstName,
        middleName, lastName, phoneNumber, faxNumber, role, gaeUserId);
  }

  public static class Builder extends Buildable.Builder<User> {
    public Builder() {}

    public Builder(User instance) {
      super(instance);
    }

    public Builder setParent(Key<Registrar> value) {
      getInstance().parent = value;
      return this;
    }

    public Builder setEmail(String value) {
      getInstance().email = value;
      return this;
    }

    public Builder setSecurityPhrase(String value) {
      getInstance().securityPhrase = value;
      return this;
    }

    public Builder setActive(boolean value){
      getInstance().active = value;
      return this;
    }

    public Builder setFirstName(String value) {
      getInstance().firstName = value;
      return this;
    }

    public Builder setMiddleName(String value) {
      getInstance().middleName = value;
      return this;
    }

    public Builder setLastName(String value) {
      getInstance().lastName = value;
      return this;
    }

    public Builder setPhoneNumber(String value) {
      getInstance().phoneNumber = value;
      return this;
    }

    public Builder setFaxNumber(String value) {
      getInstance().faxNumber = value;
      return this;
    }

    public Builder setRole(String value) {
      getInstance().role = value;
      return this;
    }

    public User build() {
      final User instance = getInstance();
      checkArgument(instance.email != null, "No email address specified");
      checkArgument(instance.securityPhrase != null, "A security phrase is required");
      checkArgument(instance.firstName != null, "First name is required");
      checkArgument(instance.lastName != null, "Last name is required");
      checkArgument(instance.role != null, "A role is required");
      checkArgument(instance.parent != null, "An associated registrar is required");
      instance.gaeUserId = GaeUserIdConverter.convertEmailAddressToGaeUserId(instance.email);
      return super.build();

    }
   }
}
