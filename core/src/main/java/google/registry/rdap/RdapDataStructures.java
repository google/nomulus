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

import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableList;
import com.google.gson.JsonArray;
import com.google.gson.JsonPrimitive;
import google.registry.rdap.AbstractJsonableObject.RestrictJsonNames;
import java.util.Optional;
import org.joda.time.DateTime;

/** Data Structures defined in RFC 9083 section 4. */
final class RdapDataStructures {

  private RdapDataStructures() {}

  /** RDAP conformance defined in 4.1 of RFC 9083. */
  @RestrictJsonNames("rdapConformance")
  static final class RdapConformance implements Jsonable {

    static final RdapConformance INSTANCE = new RdapConformance();

    private RdapConformance() {}

    @Override
    public JsonArray toJson() {
      JsonArray jsonArray = new JsonArray();
      // Conformance to RFC 9083
      jsonArray.add("rdap_level_0");

      // Conformance to the RDAP Response Profile V2.1
      // (see section 1.3)
      jsonArray.add("icann_rdap_response_profile_0");

      // Conformance to the RDAP Technical Implementation Guide V2.1
      // (see section 1.14)
      jsonArray.add("icann_rdap_technical_implementation_guide_0");

      return jsonArray;
    }
  }

  /** Links defined in 4.2 of RFC 9083. */
  @RestrictJsonNames("links[]")
  @AutoValue
  abstract static class Link extends AbstractJsonableObject {
    @JsonableElement abstract String href();

    @JsonableElement abstract Optional<String> rel();
    @JsonableElement abstract Optional<String> hreflang();
    @JsonableElement abstract Optional<String> title();
    @JsonableElement abstract Optional<String> media();
    @JsonableElement abstract Optional<String> type();
    @JsonableElement abstract Optional<String> value();

    static Builder builder() {
      return new AutoValue_RdapDataStructures_Link.Builder();
    }

    @AutoValue.Builder
    abstract static class Builder {
      abstract Builder setHref(String href);
      abstract Builder setRel(String rel);
      abstract Builder setHreflang(String hrefLang);
      abstract Builder setTitle(String title);
      abstract Builder setMedia(String media);
      abstract Builder setType(String type);
      abstract Builder setValue(String value);

      abstract Link build();
    }
  }

  /**
   * Notices and Remarks defined in 4.3 of RFC 9083.
   *
   * <p>Each has an optional "type" denoting a registered type string defined in 10.2.1. The type is
   * defined as common to both Notices and Remarks, but each item is only appropriate to one of
   * them. So we will divide all the "types" from the RFC to two enums - one for Notices and one for
   * Remarks.
   */
  private abstract static class NoticeOrRemark extends AbstractJsonableObject {
    @JsonableElement abstract Optional<String> title();
    @JsonableElement abstract ImmutableList<String> description();
    @JsonableElement abstract ImmutableList<Link> links();

    abstract static class Builder<B extends Builder<?>> {
      abstract B setTitle(String title);
      abstract B setDescription(ImmutableList<String> description);
      abstract B setDescription(String... description);
      abstract ImmutableList.Builder<Link> linksBuilder();

      @SuppressWarnings("unchecked")
      B addLink(Link link) {
        linksBuilder().add(link);
        return (B) this;
      }
    }
  }

  /**
   * Notices defined in 4.3 of RFC 9083.
   *
   * <p>A notice denotes information about the service itself or the entire response, and hence will
   * only be in the top-most object.
   */
  @AutoValue
  @RestrictJsonNames("notices[]")
  abstract static class Notice extends NoticeOrRemark {

    /**
     * Notice and Remark Type are defined in 10.2.1 of RFC 9083.
     *
     * <p>We only keep the "service or entire response" values for Notice.Type.
     */
    @RestrictJsonNames("type")
    enum Type implements Jsonable {
      RESULT_TRUNCATED_AUTHORIZATION("result set truncated due to authorization"),
      RESULT_TRUNCATED_LOAD("result set truncated due to excessive load"),
      RESULT_TRUNCATED_UNEXPLAINABLE("result set truncated due to unexplainable reasons");

      private final String rfc9083String;

      Type(String rfc9083String) {
        this.rfc9083String = rfc9083String;
      }

      @Override
      public JsonPrimitive toJson() {
        return new JsonPrimitive(rfc9083String);
      }
    }

    @JsonableElement
    abstract Optional<Notice.Type> type();

    static Builder builder() {
      return new AutoValue_RdapDataStructures_Notice.Builder();
    }

    @AutoValue.Builder
    abstract static class Builder extends NoticeOrRemark.Builder<Builder> {
      abstract Builder setType(Notice.Type type);

      abstract Notice build();
    }
  }

  /**
   * Remarks defined in 4.3 of RFC 9083.
   *
   * <p>A remark denotes information about the specific object, and hence each object has its own
   * "remarks" array.
   */
  @AutoValue
  @RestrictJsonNames("remarks[]")
  abstract static class Remark extends NoticeOrRemark {

    /**
     * Notice and Remark Type are defined in 10.2.1 of RFC 9083.
     *
     * <p>We only keep the "specific object" values for Remark.Type.
     */
    @RestrictJsonNames("type")
    enum Type implements Jsonable {
      OBJECT_TRUNCATED_AUTHORIZATION("object truncated due to authorization"),
      OBJECT_TRUNCATED_LOAD("object truncated due to excessive load"),
      OBJECT_TRUNCATED_UNEXPLAINABLE("object truncated due to unexplainable reasons"),
      // This one isn't in the "RDAP JSON Values registry", but it's in the RDAP Response Profile,
      // so I'm adding it here, but we have to ask them about it...
      OBJECT_REDACTED_AUTHORIZATION("object redacted due to authorization");

      private final String rfc9083String;

      Type(String rfc9083String) {
        this.rfc9083String = rfc9083String;
      }

      @Override
      public JsonPrimitive toJson() {
        return new JsonPrimitive(rfc9083String);
      }
    }

    @JsonableElement
    abstract Optional<Remark.Type> type();

    static Builder builder() {
      return new AutoValue_RdapDataStructures_Remark.Builder();
    }

    @AutoValue.Builder
    abstract static class Builder extends NoticeOrRemark.Builder<Builder> {
      abstract Builder setType(Remark.Type type);

      abstract Remark build();
    }
  }

  /**
   * Language Identifier defined in 4.4 of RFC 9083.
   *
   * <p>The allowed values are described in RFC5646.
   */
  @RestrictJsonNames("lang")
  enum LanguageIdentifier implements Jsonable {
    EN("en");

    private final String languageIdentifier;

    LanguageIdentifier(String languageIdentifier) {
      this.languageIdentifier = languageIdentifier;
    }

    @Override
    public JsonPrimitive toJson() {
      return new JsonPrimitive(languageIdentifier);
    }
  }

  /**
   * Events defined in 4.5 of RFC 9083.
   *
   * <p>There's a type of Event that must not have the "eventActor" (see 5.1), so we create 2
   * versions - one with and one without.
   */
  private abstract static class EventBase extends AbstractJsonableObject {
    @JsonableElement abstract EventAction eventAction();
    @JsonableElement abstract DateTime eventDate();
    @JsonableElement abstract ImmutableList<Link> links();


    abstract static class Builder<B extends Builder<?>> {
      abstract B setEventAction(EventAction eventAction);
      abstract B setEventDate(DateTime eventDate);
      abstract ImmutableList.Builder<Link> linksBuilder();

      @SuppressWarnings("unchecked")
      B addLink(Link link) {
        linksBuilder().add(link);
        return (B) this;
      }
    }
  }

  /** Status values for events specified in RFC 9083 § 10.2.3. */
  enum EventAction implements Jsonable {
    REGISTRATION("registration"),
    REREGISTRATION("reregistration"),
    LAST_CHANGED("last changed"),
    EXPIRATION("expiration"),
    DELETION("deletion"),
    REINSTANTIATION("reinstantiation"),
    TRANSFER("transfer"),
    LOCKED("locked"),
    UNLOCKED("unlocked"),
    LAST_UPDATE_OF_RDAP_DATABASE("last update of RDAP database");

    /** Value as it appears in RDAP messages. */
    private final String rfc9083String;

    EventAction(String rfc9083String) {
      this.rfc9083String = rfc9083String;
    }

    String getDisplayName() {
      return rfc9083String;
    }

    @Override
    public JsonPrimitive toJson() {
      return new JsonPrimitive(rfc9083String);
    }
  }

  /**
   * Events defined in 4.5 of RFC 9083.
   *
   * <p>There's a type of Event that MUST NOT have the "eventActor" (see 5.1), so we have this
   * object to enforce that.
   */
  @RestrictJsonNames("asEventActor[]")
  @AutoValue
  abstract static class EventWithoutActor extends EventBase {

    static Builder builder() {
      return new AutoValue_RdapDataStructures_EventWithoutActor.Builder();
    }


    @AutoValue.Builder
    abstract static class Builder extends EventBase.Builder<Builder> {
      abstract EventWithoutActor build();
    }
  }

  /** Events defined in 4.5 of RFC 9083. */
  @RestrictJsonNames("events[]")
  @AutoValue
  abstract static class Event extends EventBase {
    @JsonableElement abstract Optional<String> eventActor();

    static Builder builder() {
      return new AutoValue_RdapDataStructures_Event.Builder();
    }


    @AutoValue.Builder
    abstract static class Builder extends EventBase.Builder<Builder> {
      abstract Builder setEventActor(String eventActor);
      abstract Event build();
    }
  }

  /**
   * Status defined in 4.6 of RFC 9083.
   *
   * <p>This indicates the state of the registered object.
   *
   * <p>The allowed values are in section 10.2.2.
   */
  @RestrictJsonNames("status[]")
  enum RdapStatus implements Jsonable {

    // Status values specified in RFC 9083 § 10.2.2.
    VALIDATED("validated"),
    RENEW_PROHIBITED("renew prohibited"),
    UPDATE_PROHIBITED("update prohibited"),
    TRANSFER_PROHIBITED("transfer prohibited"),
    DELETE_PROHIBITED("delete prohibited"),
    PROXY("proxy"),
    PRIVATE("private"),
    REMOVED("removed"),
    OBSCURED("obscured"),
    ASSOCIATED("associated"),
    ACTIVE("active"),
    INACTIVE("inactive"),
    LOCKED("locked"),
    PENDING_CREATE("pending create"),
    PENDING_RENEW("pending renew"),
    PENDING_TRANSFER("pending transfer"),
    PENDING_UPDATE("pending update"),
    PENDING_DELETE("pending delete"),

    // Additional status values defined in
    // https://tools.ietf.org/html/draft-ietf-regext-epp-rdap-status-mapping-01.
    ADD_PERIOD("add period"),
    AUTO_RENEW_PERIOD("auto renew period"),
    CLIENT_DELETE_PROHIBITED("client delete prohibited"),
    CLIENT_HOLD("client hold"),
    CLIENT_RENEW_PROHIBITED("client renew prohibited"),
    CLIENT_TRANSFER_PROHIBITED("client transfer prohibited"),
    CLIENT_UPDATE_PROHIBITED("client update prohibited"),
    PENDING_RESTORE("pending restore"),
    REDEMPTION_PERIOD("redemption period"),
    RENEW_PERIOD("renew period"),
    SERVER_DELETE_PROHIBITED("server deleted prohibited"),
    SERVER_RENEW_PROHIBITED("server renew prohibited"),
    SERVER_TRANSFER_PROHIBITED("server transfer prohibited"),
    SERVER_UPDATE_PROHIBITED("server update prohibited"),
    SERVER_HOLD("server hold"),
    TRANSFER_PERIOD("transfer period");

    /** Value as it appears in RDAP messages. */
    private final String rfc9083String;

    RdapStatus(String rfc9083String) {
      this.rfc9083String = rfc9083String;
    }

    String getDisplayName() {
      return rfc9083String;
    }

    @Override
    public JsonPrimitive toJson() {
      return new JsonPrimitive(rfc9083String);
    }
  }

  /**
   * Port 43 WHOIS Server defined in 4.7 of RFC 9083.
   *
   * <p>This contains the fully qualifies host name of IP address of the WHOIS RFC3912 server where
   * the containing object instance may be found.
   */
  @RestrictJsonNames("port43")
  @AutoValue
  abstract static class Port43WhoisServer implements Jsonable {
    abstract String port43();

    @Override
    public JsonPrimitive toJson() {
      return new JsonPrimitive(port43());
    }

    static Port43WhoisServer create(String port43) {
      return new AutoValue_RdapDataStructures_Port43WhoisServer(port43);
    }
  }

  /**
   * Public IDs defined in 4.8 of RFC 9083.
   *
   * <p>Maps a public identifier to an object class.
   */
  @RestrictJsonNames("publicIds[]")
  @AutoValue
  abstract static class PublicId extends AbstractJsonableObject {
    @RestrictJsonNames("type")
    enum Type implements Jsonable {
      IANA_REGISTRAR_ID("IANA Registrar ID");

      private final String rfc9083String;

      Type(String rfc9083String) {
        this.rfc9083String = rfc9083String;
      }

      @Override
      public JsonPrimitive toJson() {
        return new JsonPrimitive(rfc9083String);
      }
    }

    @JsonableElement
    abstract PublicId.Type type();

    @JsonableElement abstract String identifier();

    static PublicId create(PublicId.Type type, String identifier) {
      return new AutoValue_RdapDataStructures_PublicId(type, identifier);
    }
  }

  /**
   * Object Class Name defined in 4.7 of RFC 9083.
   *
   * <p>Identifies the type of the object being processed. Is REQUIRED in all RDAP response objects,
   * but not so for internal objects whose type can be inferred by their key name in the enclosing
   * object.
   */
  @RestrictJsonNames("objectClassName")
  enum ObjectClassName implements Jsonable {
    /** Defined in 5.1 of RFC 9083. */
    ENTITY("entity"),
    /** Defined in 5.2 of RFC 9083. */
    NAMESERVER("nameserver"),
    /** Defined in 5.3 of RFC 9083. */
    DOMAIN("domain"),
    /** Defined in 5.4 of RFC 9083. Only relevant for Registrars, so isn't implemented here. */
    IP_NETWORK("ip network"),
    /** Defined in 5.5 of RFC 9083. Only relevant for Registrars, so isn't implemented here. */
    AUTONOMUS_SYSTEM("autnum");

    private final String className;

    ObjectClassName(String className) {
      this.className = className;
    }

    @Override
    public JsonPrimitive toJson() {
      return new JsonPrimitive(className);
    }
  }
}
