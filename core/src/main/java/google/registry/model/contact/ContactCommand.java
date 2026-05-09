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

package google.registry.model.contact;

import static com.google.common.base.Preconditions.checkState;
import static google.registry.util.CollectionUtils.nullToEmpty;
import static google.registry.util.CollectionUtils.nullToEmptyImmutableCopy;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import google.registry.model.Buildable;
import google.registry.model.EppResource;
import google.registry.model.ImmutableObject;
import google.registry.model.contact.PostalInfo.Type;
import google.registry.model.eppcommon.StatusValue;
import google.registry.model.eppinput.ResourceCommand.AbstractSingleResourceCommand;
import google.registry.model.eppinput.ResourceCommand.ResourceCheck;
import google.registry.model.eppinput.ResourceCommand.ResourceCreateOrChange;
import google.registry.model.eppinput.ResourceCommand.ResourceUpdate;
import google.registry.model.eppinput.SingleResourceCommand;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlRootElement;
import jakarta.xml.bind.annotation.XmlTransient;
import jakarta.xml.bind.annotation.XmlType;
import jakarta.xml.bind.annotation.adapters.CollapsedStringAdapter;
import jakarta.xml.bind.annotation.adapters.XmlJavaTypeAdapter;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** A collection of (vestigial) Contact commands. */
public class ContactCommand {

  /** The fields on "chgType" from <a href="http://tools.ietf.org/html/rfc5733">RFC5733</a>. */
  @XmlTransient
  public static class ContactCreateOrChange extends ImmutableObject
      implements ResourceCreateOrChange<EppResource.Builder<?, ?>> {

    /** Postal info for the contact. */
    List<PostalInfo> postalInfo;

    /** Contact’s voice number. */
    ContactPhoneNumber voice;

    /** Contact’s fax number. */
    ContactPhoneNumber fax;

    /** Contact’s email address. */
    @XmlJavaTypeAdapter(CollapsedStringAdapter.class)
    String email;

    /** Authorization info (aka transfer secret) of the contact. */
    ContactAuthInfo authInfo;

    /** Disclosure policy. */
    Disclose disclose;

    /** Helper method to move between the postal infos list and the individual getters. */
    protected Map<Type, PostalInfo> getPostalInfosAsMap() {
      // There can be no more than 2 postalInfos (enforced by the schema), and if there are 2 they
      // must be of different types (not enforced). If the type is repeated, uniqueIndex will throw.
      checkState(nullToEmpty(postalInfo).size() <= 2);
      return Maps.uniqueIndex(nullToEmpty(postalInfo), PostalInfo::getType);
    }

    public ContactPhoneNumber getVoice() {
      return voice;
    }

    public ContactPhoneNumber getFax() {
      return fax;
    }

    public String getEmail() {
      return email;
    }

    public ContactAuthInfo getAuthInfo() {
      return authInfo;
    }

    public Disclose getDisclose() {
      return disclose;
    }

    public PostalInfo getInternationalizedPostalInfo() {
      return getPostalInfosAsMap().get(Type.INTERNATIONALIZED);
    }

    public PostalInfo getLocalizedPostalInfo() {
      return getPostalInfosAsMap().get(Type.LOCALIZED);
    }
  }

  /** An abstract contact command that contains authorization info. */
  @XmlTransient
  public static class AbstractContactAuthCommand extends AbstractSingleResourceCommand {
    /** Authorization info used to validate if client has permissions to perform this operation. */
    ContactAuthInfo authInfo;

    @Override
    public ContactAuthInfo getAuthInfo() {
      return authInfo;
    }
  }

  /**
   * A create command for a (vestigial) Contact, mapping "createType" from <a
   * href="http://tools.ietf.org/html/rfc5733">RFC5733</a>}.
   */
  @XmlType(propOrder = {"contactId", "postalInfo", "voice", "fax", "email", "authInfo", "disclose"})
  @XmlRootElement
  public static class Create extends ContactCreateOrChange
      implements SingleResourceCommand, ResourceCreateOrChange<EppResource.Builder<?, ?>> {
    /**
     * Unique identifier for this contact.
     *
     * <p>This is only unique in the sense that for any given lifetime specified as the time range
     * from (creationTime, deletionTime) there can only be one contact in the database with this id.
     * However, there can be many contacts with the same id and non-overlapping lifetimes.
     */
    @XmlElement(name = "id")
    String contactId;

    @Override
    public String getTargetId() {
      return contactId;
    }

    @Override
    public ContactAuthInfo getAuthInfo() {
      return authInfo;
    }

    @Override
    public Create clone() {
      try {
        Create clone = (Create) super.clone();
        clone.hashCode = null;
        return clone;
      } catch (CloneNotSupportedException e) {
        throw new AssertionError();
      }
    }

    /** Builder for {@link Create}. */
    public static class Builder extends Buildable.Builder<Create> {

      /** Sets the contact identifier. */
      public Builder setContactId(String contactId) {
        getInstance().contactId = contactId;
        return this;
      }

      /** Sets the postal information. */
      public Builder setPostalInfo(List<PostalInfo> postalInfo) {
        getInstance().postalInfo = postalInfo;
        return this;
      }

      /** Sets the voice number. */
      public Builder setVoice(ContactPhoneNumber voice) {
        getInstance().voice = voice;
        return this;
      }

      /** Sets the fax number. */
      public Builder setFax(ContactPhoneNumber fax) {
        getInstance().fax = fax;
        return this;
      }

      /** Sets the email address. */
      public Builder setEmail(String email) {
        getInstance().email = email;
        return this;
      }

      /** Sets the authorization info. */
      public Builder setAuthInfo(ContactAuthInfo authInfo) {
        getInstance().authInfo = authInfo;
        return this;
      }

      /** Sets the disclosure policy. */
      public Builder setDisclose(Disclose disclose) {
        getInstance().disclose = disclose;
        return this;
      }
    }
  }

  /** A delete command for a (vestigial) Contact. */
  @XmlRootElement
  public static class Delete extends AbstractSingleResourceCommand {}

  /** An info request for a (vestigial) Contact. */
  @XmlRootElement
  @XmlType(propOrder = {"targetId", "authInfo"})
  public static class Info extends AbstractContactAuthCommand {}

  /** A check request for (vestigial) Contact. */
  @XmlRootElement
  public static class Check extends ResourceCheck {}

  /** A transfer operation for a (vestigial) Contact. */
  @XmlRootElement
  @XmlType(propOrder = {"targetId", "authInfo"})
  public static class Transfer extends AbstractContactAuthCommand {}

  /** An update to a (vestigial) Contact. */
  @XmlRootElement
  @XmlType(propOrder = {"targetId", "innerAdd", "innerRemove", "innerChange"})
  public static class Update
      extends ResourceUpdate<Update.ContactAddRemove, EppResource.Builder<?, ?>, Update.Change> {

    @XmlElement(name = "chg")
    protected Change innerChange;

    @XmlElement(name = "add")
    protected ContactAddRemove innerAdd;

    @XmlElement(name = "rem")
    protected ContactAddRemove innerRemove;

    @Override
    protected Change getNullableInnerChange() {
      return innerChange;
    }

    @Override
    protected ContactAddRemove getNullableInnerAdd() {
      return innerAdd;
    }

    @Override
    protected ContactAddRemove getNullableInnerRemove() {
      return innerRemove;
    }

    /** Builder for {@link Update}. */
    public static class Builder extends Buildable.Builder<Update> {

      /** Sets the target contact identifier. */
      public Builder setTargetId(String targetId) {
        getInstance().targetId = targetId;
        return this;
      }

      /** Sets the change component. */
      public Builder setInnerChange(Change innerChange) {
        getInstance().innerChange = innerChange;
        return this;
      }

      /** Sets the add component. */
      public Builder setInnerAdd(ContactAddRemove innerAdd) {
        getInstance().innerAdd = innerAdd;
        return this;
      }

      /** Sets the remove component. */
      public Builder setInnerRemove(ContactAddRemove innerRemove) {
        getInstance().innerRemove = innerRemove;
        return this;
      }
    }

    /** The inner change type on a contact update command. */
    public static class ContactAddRemove extends ResourceUpdate.AddRemove {
      @XmlElement(name = "status")
      Set<StatusValue> statusValues;

      @Override
      public void setStatusValues(ImmutableSet<StatusValue> statusValues) {
        this.statusValues = statusValues;
      }

      @Override
      public ImmutableSet<StatusValue> getStatusValues() {
        return nullToEmptyImmutableCopy(statusValues);
      }

      @Override
      public ContactAddRemove clone() {
        ContactAddRemove clone = (ContactAddRemove) super.clone();
        clone.hashCode = null;
        return clone;
      }
    }

    /** The inner change type on a contact update command. */
    @XmlType(propOrder = {"postalInfo", "voice", "fax", "email", "authInfo", "disclose"})
    public static class Change extends ContactCreateOrChange {
      @Override
      public Change clone() {
        try {
          Change clone = (Change) super.clone();
          clone.hashCode = null;
          return clone;
        } catch (CloneNotSupportedException e) {
          throw new AssertionError();
        }
      }
    }
  }
}
