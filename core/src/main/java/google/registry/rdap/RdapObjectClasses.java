// Copyright 2019 The Nomulus Authors. All Rights Reserved.
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

package google.registry.rdap;

import static google.registry.util.DomainNameUtils.ACE_PREFIX;

import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Ordering;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import google.registry.model.domain.secdns.DelegationSignerData;
import google.registry.rdap.AbstractJsonableObject.RestrictJsonNames;
import google.registry.rdap.RdapDataStructures.Event;
import google.registry.rdap.RdapDataStructures.EventWithoutActor;
import google.registry.rdap.RdapDataStructures.LanguageIdentifier;
import google.registry.rdap.RdapDataStructures.Link;
import google.registry.rdap.RdapDataStructures.Notice;
import google.registry.rdap.RdapDataStructures.ObjectClassName;
import google.registry.rdap.RdapDataStructures.Port43WhoisServer;
import google.registry.rdap.RdapDataStructures.PublicId;
import google.registry.rdap.RdapDataStructures.RdapConformance;
import google.registry.rdap.RdapDataStructures.RdapStatus;
import google.registry.rdap.RdapDataStructures.Remark;
import google.registry.util.Idn;
import java.util.Optional;

/** Object Classes defined in RFC 9083 section 5. */
final class RdapObjectClasses {

  /**
   * Temporary implementation of VCards.
   *
   * Will create a better implementation soon.
   */
  @RestrictJsonNames({})
  @AutoValue
  abstract static class Vcard implements Jsonable {
    abstract String property();
    abstract ImmutableMap<String, ImmutableList<String>> parameters();
    abstract String valueType();
    abstract JsonElement value();

    static Vcard create(
        String property,
        ImmutableMap<String, ImmutableList<String>> parameters,
        String valueType,
        JsonElement value) {
      return new AutoValue_RdapObjectClasses_Vcard(property, parameters, valueType, value);
    }

    static Vcard create(
        String property,
        ImmutableMap<String, ImmutableList<String>> parameters,
        String valueType,
        String value) {
      return create(property, parameters, valueType, new JsonPrimitive(value));
    }

    static Vcard create(String property, String valueType, JsonElement value) {
      return create(property, ImmutableMap.of(), valueType, value);
    }

    static Vcard create(String property, String valueType, String value) {
      return create(property, valueType, new JsonPrimitive(value));
    }

    @Override
    public JsonArray toJson() {
      JsonArray jsonArray = new JsonArray();
      jsonArray.add(property());
      jsonArray.add(new Gson().toJsonTree(parameters()));
      jsonArray.add(valueType());
      jsonArray.add(value());
      return jsonArray;
    }
  }

  @RestrictJsonNames("vcardArray")
  @AutoValue
  abstract static class VcardArray implements Jsonable {

    private static final String VCARD_VERSION_NUMBER = "4.0";
    private static final Vcard VCARD_ENTRY_VERSION =
        Vcard.create("version", "text", VCARD_VERSION_NUMBER);

    abstract ImmutableList<Vcard> vcards();

    @Override
    public JsonArray toJson() {
      JsonArray jsonArray = new JsonArray();
      jsonArray.add("vcard");
      JsonArray jsonVcardsArray = new JsonArray();
      jsonVcardsArray.add(VCARD_ENTRY_VERSION.toJson());
      vcards().forEach(vcard -> jsonVcardsArray.add(vcard.toJson()));
      jsonArray.add(jsonVcardsArray);
      return jsonArray;
    }

    static Builder builder() {
      return new AutoValue_RdapObjectClasses_VcardArray.Builder();
    }

    @AutoValue.Builder
    abstract static class Builder {
      abstract ImmutableList.Builder<Vcard> vcardsBuilder();
      Builder add(Vcard vcard) {
        vcardsBuilder().add(vcard);
        return this;
      }

      abstract VcardArray build();
    }
  }

  /**
   * Indication of what type of boilerplate notices are required for the RDAP JSON messages. The
   * ICANN RDAP Profile specifies that, for instance, domain name responses should include a remark
   * about domain status codes. So we need to know when to include such boilerplate. On the other
   * hand, remarks are not allowed except in domain, nameserver and entity objects, so we need to
   * suppress them for other types of responses (e.g. help).
   */
  public enum BoilerplateType {
    DOMAIN,
    NAMESERVER,
    ENTITY,
    OTHER
  }

  /**
   * An object that can be used to create a TopLevelReply.
   *
   * All Actions need to return an object of this type.
   */
  abstract static class ReplyPayloadBase extends AbstractJsonableObject {
    final BoilerplateType boilerplateType;

    ReplyPayloadBase(BoilerplateType boilerplateType) {
      this.boilerplateType = boilerplateType;
    }
  }

  /**
   * The Top Level JSON reply, Adds the required top-level boilerplate to a ReplyPayloadBase.
   *
   * <p>RFC 9083 specifies that the top-level object should include an entry indicating the
   * conformance level. ICANN RDAP spec for 15feb19 mandates several additional entries, in sections
   * 2.6.3, 2.11 of the Response Profile and 3.3, 3.5, of the Technical Implementation Guide.
   */
  @AutoValue
  @RestrictJsonNames({})
  abstract static class TopLevelReplyObject extends AbstractJsonableObject {
    @JsonableElement("rdapConformance")
    static final RdapConformance RDAP_CONFORMANCE = RdapConformance.INSTANCE;

    @JsonableElement("*") abstract ReplyPayloadBase aAreplyObject();
    @JsonableElement("notices[]") abstract Notice aTosNotice();

    @JsonableElement("notices") ImmutableList<Notice> boilerplateNotices() {
      switch (aAreplyObject().boilerplateType) {
        case DOMAIN:
          return RdapIcannStandardInformation.domainBoilerplateNotices;
        case NAMESERVER:
        case ENTITY:
          return RdapIcannStandardInformation.nameserverAndEntityBoilerplateNotices;
        default: // things other than domains, nameservers and entities do not yet have boilerplate
          return ImmutableList.of();
      }
    }

    static TopLevelReplyObject create(ReplyPayloadBase replyObject, Notice tosNotice) {
      return new AutoValue_RdapObjectClasses_TopLevelReplyObject(replyObject, tosNotice);
    }
  }

  /**
   * A base object shared by Entity, Nameserver, and Domain.
   *
   * <p>Not part of the spec, but seems convenient.
   */
  private abstract static class RdapObjectBase extends ReplyPayloadBase {
    @JsonableElement final ObjectClassName objectClassName;

    @JsonableElement abstract Optional<String> handle();
    @JsonableElement abstract ImmutableList<PublicId> publicIds();
    @JsonableElement abstract ImmutableList<RdapEntity> entities();
    @JsonableElement abstract ImmutableList<RdapStatus> status();
    @JsonableElement abstract ImmutableList<Remark> remarks();
    @JsonableElement abstract ImmutableList<Link> links();
    @JsonableElement abstract ImmutableList<Event> events();

    /**
     * Required event for all response objects, but not for internal objects.
     *
     * <p>Meaning it's required in, e.g., an RdapNameserver object that is a response to a
     * Nameserver query, but not to an RdapNameserver that's part of an RdapDomain response to a
     * Domain query.
     *
     * <p>RDAP Response Profile 2.3.1.3, 3.3, 4.4
     */
    @JsonableElement("events[]")
    abstract Optional<Event> lastUpdateOfRdapDatabaseEvent();

    /**
     * WHOIS server displayed in RDAP query responses.
     *
     * <p>As per Gustavo Lozano of ICANN, this should be omitted, but the ICANN operational profile
     * doesn't actually say that, so it's good to have the ability to reinstate this field if
     * necessary.
     */
    @JsonableElement
    abstract Optional<Port43WhoisServer> port43();

    RdapObjectBase(BoilerplateType boilerplateType, ObjectClassName objectClassName) {
      super(boilerplateType);
      this.objectClassName = objectClassName;
    }


    abstract static class Builder<B extends Builder<?>> {
      abstract B setHandle(String handle);
      abstract ImmutableList.Builder<PublicId> publicIdsBuilder();
      abstract ImmutableList.Builder<RdapEntity> entitiesBuilder();
      abstract ImmutableList.Builder<RdapStatus> statusBuilder();
      abstract ImmutableList.Builder<Remark> remarksBuilder();
      abstract ImmutableList.Builder<Link> linksBuilder();
      abstract B setPort43(Port43WhoisServer port43);
      abstract ImmutableList.Builder<Event> eventsBuilder();

      abstract B setLastUpdateOfRdapDatabaseEvent(Event event);
    }
  }

  /**
   * The Entity Object Class defined in 5.1 of RFC 9083.
   *
   * <p>Entities are used both for Contacts and for Registrars. We will create different subobjects
   * for each one for type safety.
   *
   * <p>We're missing the "autnums" and "networks" fields
   */
  @RestrictJsonNames({"entities[]", "entitySearchResults[]"})
  abstract static class RdapEntity extends RdapObjectBase {

    /** Role values specified in RFC 9083 § 10.2.4. */
    @RestrictJsonNames("roles[]")
    enum Role implements Jsonable {
      REGISTRANT("registrant"),
      TECH("technical"),
      ADMIN("administrative"),
      ABUSE("abuse"),
      BILLING("billing"),
      REGISTRAR("registrar"),
      RESELLER("reseller"),
      SPONSOR("sponsor"),
      PROXY("proxy"),
      NOTIFICATIONS("notifications"),
      NOC("noc");

      /** Value as it appears in RDAP messages. */
      final String rfc9083String;

      Role(String rfc9083String) {
        this.rfc9083String = rfc9083String;
      }

      @Override
      public JsonPrimitive toJson() {
        return new JsonPrimitive(rfc9083String);
      }
    }

    RdapEntity() {
      super(BoilerplateType.ENTITY, ObjectClassName.ENTITY);
    }

    @JsonableElement abstract Optional<VcardArray> vcardArray();
    @JsonableElement abstract ImmutableSet<Role> roles();
    @JsonableElement abstract ImmutableList<EventWithoutActor> asEventActor();

    private abstract static class Builder<B extends Builder<B>> extends RdapObjectBase.Builder<B> {
      abstract B setVcardArray(VcardArray vcardArray);

      abstract ImmutableSet.Builder<Role> rolesBuilder();

      abstract ImmutableList.Builder<EventWithoutActor> asEventActorBuilder();
    }
  }

  /**
   * Registrar version of the Entity Object Class defined in 5.1 of RFC 9083.
   *
   * <p>Entities are used both for Contacts and for Registrars. We will create different subobjects
   * for each one for type safety.
   */
  @AutoValue
  abstract static class RdapRegistrarEntity extends RdapEntity {

    static Builder builder() {
      return new AutoValue_RdapObjectClasses_RdapRegistrarEntity.Builder();
    }

    @AutoValue.Builder
    abstract static class Builder extends RdapEntity.Builder<Builder> {
      abstract RdapRegistrarEntity build();
    }
  }

  /**
   * Contact version of the Entity Object Class defined in 5.1 of RFC 9083.
   *
   * <p>Entities are used both for Contacts and for Registrars. We will create different subobjects
   * for each one for type safety.
   */
  @AutoValue
  abstract static class RdapContactEntity extends RdapEntity {

    static Builder builder() {
      return new AutoValue_RdapObjectClasses_RdapContactEntity.Builder();
    }

    @AutoValue.Builder
    abstract static class Builder extends RdapEntity.Builder<Builder> {
      abstract RdapContactEntity build();
    }
  }

  /**
   * A base object shared by Nameserver, and Domain.
   *
   * <p>Takes care of the name and unicode field.
   *
   * <p>See RDAP Response Profile 15feb19 sections 2.1 and 4.1.
   *
   * <p>Note the ldhName field is only required for non-IDN names or IDN names when the query was an
   * A-label. It is optional for IDN names when the query was a U-label. Because we don't want to
   * remember the query when building the results, we always show it.
   *
   * <p>Not part of the spec, but seems convenient.
   */
  private abstract static class RdapNamedObjectBase extends RdapObjectBase {

    @JsonableElement abstract String ldhName();

    @JsonableElement final Optional<String> unicodeName() {
      // Only include the unicodeName field if there are unicode characters.
      //
      // TODO(b/127490882) Consider removing the condition (i.e. always having the unicodeName
      // field)
      if (!hasUnicodeComponents(ldhName())) {
        return Optional.empty();
      }
      return Optional.of(Idn.toUnicode(ldhName()));
    }

    private static boolean hasUnicodeComponents(String fullyQualifiedName) {
      return fullyQualifiedName.startsWith(ACE_PREFIX)
          || fullyQualifiedName.contains("." + ACE_PREFIX);
    }

    abstract static class Builder<B extends Builder<?>> extends RdapObjectBase.Builder<B> {
      abstract B setLdhName(String ldhName);
    }

    RdapNamedObjectBase(BoilerplateType boilerplateType, ObjectClassName objectClassName) {
      super(boilerplateType, objectClassName);
    }
  }

  /** The Nameserver Object Class defined in 5.2 of RFC 9083. */
  @RestrictJsonNames({"nameservers[]", "nameserverSearchResults[]"})
  @AutoValue
  abstract static class RdapNameserver extends RdapNamedObjectBase {

    @JsonableElement Optional<IpAddresses> ipAddresses() {
      if (ipv6().isEmpty() && ipv4().isEmpty()) {
        return Optional.empty();
      }
      return Optional.of(new IpAddresses());
    }

    abstract ImmutableList<String> ipv6();
    abstract ImmutableList<String> ipv4();

    class IpAddresses extends AbstractJsonableObject {
      @JsonableElement ImmutableList<String> v6() {
        return Ordering.natural().immutableSortedCopy(ipv6());
      }

      @JsonableElement ImmutableList<String> v4() {
        return Ordering.natural().immutableSortedCopy(ipv4());
      }
    }

    RdapNameserver() {
      super(BoilerplateType.NAMESERVER, ObjectClassName.NAMESERVER);
    }

    static Builder builder() {
      return new AutoValue_RdapObjectClasses_RdapNameserver.Builder();
    }

    @AutoValue.Builder
    abstract static class Builder extends RdapNamedObjectBase.Builder<Builder> {
      abstract ImmutableList.Builder<String> ipv6Builder();
      abstract ImmutableList.Builder<String> ipv4Builder();

      abstract RdapNameserver build();
    }
  }

  /** Object defined in RFC 9083 section 5.3, only used for RdapDomain. */
  @RestrictJsonNames("secureDNS")
  @AutoValue
  abstract static class SecureDns extends AbstractJsonableObject {
    @RestrictJsonNames("dsData[]")
    @AutoValue
    abstract static class DsData extends AbstractJsonableObject {
      @JsonableElement
      abstract int keyTag();

      @JsonableElement
      abstract int algorithm();

      @JsonableElement
      abstract String digest();

      @JsonableElement
      abstract int digestType();

      static DsData create(DelegationSignerData dsData) {
        return new AutoValue_RdapObjectClasses_SecureDns_DsData(
            dsData.getKeyTag(),
            dsData.getAlgorithm(),
            dsData.getDigestAsString(),
            dsData.getDigestType());
      }
    }

    /** true if the zone has been signed, false otherwise. */
    @JsonableElement
    abstract boolean zoneSigned();

    /** true if there are DS records in the parent, false otherwise. */
    @JsonableElement
    boolean delegationSigned() {
      return !dsData().isEmpty();
    }

    /**
     * an integer representing the signature lifetime in seconds to be used when creating the RRSIG
     * DS record in the parent zone [RFC5910].
     *
     * <p>Note that although it isn't given as optional in RFC 9083, in RFC5910 it's mentioned as
     * optional. Also, our code doesn't support it at all - so it's set to always be empty.
     */
    @JsonableElement
    Optional<Integer> maxSigLife() {
      return Optional.empty();
    }

    @JsonableElement
    abstract ImmutableList<DsData> dsData();

    static Builder builder() {
      return new AutoValue_RdapObjectClasses_SecureDns.Builder();
    }

    abstract Builder toBuilder();

    @AutoValue.Builder
    abstract static class Builder {
      abstract Builder setZoneSigned(boolean zoneSigned);

      abstract ImmutableList.Builder<DsData> dsDataBuilder();

      Builder addDsData(DelegationSignerData dsData) {
        dsDataBuilder().add(DsData.create(dsData));
        return this;
      }

      abstract SecureDns build();
    }
  }

  /**
   * The Domain Object Class defined in 5.3 of RFC 9083.
   *
   * <p>We're missing the "variants", "secureDNS", "network" fields
   */
  @RestrictJsonNames("domainSearchResults[]")
  @AutoValue
  abstract static class RdapDomain extends RdapNamedObjectBase {

    @JsonableElement abstract ImmutableList<RdapNameserver> nameservers();

    @JsonableElement("secureDNS")
    abstract Optional<SecureDns> secureDns();

    RdapDomain() {
      super(BoilerplateType.DOMAIN, ObjectClassName.DOMAIN);
    }

    static Builder builder() {
      return new AutoValue_RdapObjectClasses_RdapDomain.Builder();
    }

    @AutoValue.Builder
    abstract static class Builder extends RdapNamedObjectBase.Builder<Builder> {
      abstract ImmutableList.Builder<RdapNameserver> nameserversBuilder();

      abstract Builder setSecureDns(SecureDns secureDns);

      abstract RdapDomain build();
    }
  }

  /** Error Response Body defined in 6 of RFC 9083. */
  @RestrictJsonNames({})
  @AutoValue
  abstract static class ErrorResponse extends ReplyPayloadBase {

    @JsonableElement final LanguageIdentifier lang = LanguageIdentifier.EN;

    @JsonableElement abstract int errorCode();
    @JsonableElement abstract String title();
    @JsonableElement abstract ImmutableList<String> description();

    ErrorResponse() {
      super(BoilerplateType.OTHER);
    }

    static ErrorResponse create(int status, String title, String description) {
      return new AutoValue_RdapObjectClasses_ErrorResponse(
          status, title, ImmutableList.of(description));
    }
  }

  /**
   * Help Response defined in 7 of RFC 9083.
   *
   * <p>The helpNotice field is optional, because if the user requests the TOS - that's already
   * given by the boilerplate of TopLevelReplyObject so we don't want to give it again.
   */
  @RestrictJsonNames({})
  @AutoValue
  abstract static class HelpResponse extends ReplyPayloadBase {
    @JsonableElement("notices[]") abstract Optional<Notice> helpNotice();

    HelpResponse() {
      super(BoilerplateType.OTHER);
    }

    static HelpResponse create(Optional<Notice> helpNotice) {
      return new AutoValue_RdapObjectClasses_HelpResponse(helpNotice);
    }
  }

  private RdapObjectClasses() {}
}
