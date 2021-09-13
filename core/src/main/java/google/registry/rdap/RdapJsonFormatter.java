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

package google.registry.rdap;

import static com.google.common.base.Predicates.not;
import static com.google.common.base.Strings.nullToEmpty;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static com.google.common.collect.ImmutableSetMultimap.toImmutableSetMultimap;
import static google.registry.model.EppResourceUtils.isLinked;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.persistence.transaction.TransactionManagerUtil.transactIfJpaTm;
import static google.registry.rdap.RdapIcannStandardInformation.CONTACT_REDACTED_VALUE;
import static google.registry.util.CollectionUtils.union;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.Maps;
import com.google.common.collect.Ordering;
import com.google.common.collect.Streams;
import com.google.common.flogger.FluentLogger;
import com.google.common.net.InetAddresses;
import com.google.gson.JsonArray;
import google.registry.config.RegistryConfig.Config;
import google.registry.model.EppResource;
import google.registry.model.contact.ContactAddress;
import google.registry.model.contact.ContactPhoneNumber;
import google.registry.model.contact.ContactResource;
import google.registry.model.contact.PostalInfo;
import google.registry.model.domain.DesignatedContact;
import google.registry.model.domain.DesignatedContact.Type;
import google.registry.model.domain.DomainBase;
import google.registry.model.eppcommon.Address;
import google.registry.model.eppcommon.StatusValue;
import google.registry.model.host.HostResource;
import google.registry.model.registrar.Registrar;
import google.registry.model.registrar.RegistrarAddress;
import google.registry.model.registrar.RegistrarContact;
import google.registry.model.reporting.HistoryEntry;
import google.registry.model.reporting.HistoryEntryDao;
import google.registry.persistence.VKey;
import google.registry.rdap.RdapDataStructures.Event;
import google.registry.rdap.RdapDataStructures.EventAction;
import google.registry.rdap.RdapDataStructures.Link;
import google.registry.rdap.RdapDataStructures.Notice;
import google.registry.rdap.RdapDataStructures.PublicId;
import google.registry.rdap.RdapDataStructures.RdapStatus;
import google.registry.rdap.RdapObjectClasses.RdapContactEntity;
import google.registry.rdap.RdapObjectClasses.RdapDomain;
import google.registry.rdap.RdapObjectClasses.RdapEntity;
import google.registry.rdap.RdapObjectClasses.RdapNameserver;
import google.registry.rdap.RdapObjectClasses.RdapRegistrarEntity;
import google.registry.rdap.RdapObjectClasses.SecureDns;
import google.registry.rdap.RdapObjectClasses.Vcard;
import google.registry.rdap.RdapObjectClasses.VcardArray;
import google.registry.request.FullServletPath;
import google.registry.util.Clock;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import javax.annotation.Nullable;
import javax.inject.Inject;
import org.joda.time.DateTime;

/**
 * Helper class to create RDAP JSON objects for various registry entities and objects.
 *
 * <p>The JSON format specifies that entities should be supplied with links indicating how to fetch
 * them via RDAP, which requires the URL to the RDAP server. The linkBase parameter, passed to many
 * of the methods, is used as the first part of the link URL. For instance, if linkBase is
 * "http://rdap.org/dir/", the link URLs will look like "http://rdap.org/dir/domain/XXXX", etc.
 *
 * @see <a href="https://tools.ietf.org/html/rfc9083">RFC 9083: JSON Responses for the Registration
 *     Data Access Protocol (RDAP)</a>
 */
public class RdapJsonFormatter {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private DateTime requestTime = null;

  @Inject @Config("rdapTos") ImmutableList<String> rdapTos;
  @Inject @Config("rdapTosStaticUrl") @Nullable String rdapTosStaticUrl;
  @Inject @FullServletPath String fullServletPath;
  @Inject RdapAuthorization rdapAuthorization;
  @Inject Clock clock;
  @Inject RdapJsonFormatter() {}

  /**
   * What type of data to generate.
   *
   * <p>Summary data includes only information about the object itself, while full data includes
   * associated items (e.g. for domains, full data includes the hosts, contacts and history entries
   * connected with the domain).
   *
   * <p>Summary data is appropriate for search queries which return many results, to avoid load on
   * the system. According to the ICANN operational profile, a remark must be attached to the
   * returned object indicating that it includes only summary data.
   */
  public enum OutputDataType {
    /**
     * The full information about an RDAP object.
     *
     * <p>Reserved to cases when this object is the only result of a query - either queried
     * directly, or the sole result of a search query.
     */
    FULL,
    /**
     * The minimal information about an RDAP object that is allowed as a reply.
     *
     * <p>Reserved to cases when this object is one of many results of a search query.
     *
     * <p>We want to minimize the size of the reply, and also minimize the Datastore queries needed
     * to generate these replies since we might have a lot of these objects to return.
     *
     * <p>Each object with a SUMMARY type will have a remark with a direct link to itself, which
     * will return the FULL result.
     */
    SUMMARY,
    /**
     * The object isn't the subject of the query, but is rather a sub-object of the actual reply.
     *
     * <p>These objects have less required fields in the RDAP spec, and hence can be even smaller
     * than the SUMMARY objects.
     *
     * <p>Like SUMMARY objects, these objects will also have a remark with a direct link to itself,
     * which will return the FULL result.
     */
    INTERNAL
  }

  /** Map of EPP status values to the RDAP equivalents. */
  private static final ImmutableMap<StatusValue, RdapStatus> STATUS_TO_RDAP_STATUS_MAP =
      new ImmutableMap.Builder<StatusValue, RdapStatus>()
          // RdapStatus.ADD_PERIOD not defined in our system
          // RdapStatus.AUTO_RENEW_PERIOD not defined in our system
          .put(StatusValue.CLIENT_DELETE_PROHIBITED, RdapStatus.CLIENT_DELETE_PROHIBITED)
          .put(StatusValue.CLIENT_HOLD, RdapStatus.CLIENT_HOLD)
          .put(StatusValue.CLIENT_RENEW_PROHIBITED, RdapStatus.CLIENT_RENEW_PROHIBITED)
          .put(StatusValue.CLIENT_TRANSFER_PROHIBITED, RdapStatus.CLIENT_TRANSFER_PROHIBITED)
          .put(StatusValue.CLIENT_UPDATE_PROHIBITED, RdapStatus.CLIENT_UPDATE_PROHIBITED)
          .put(StatusValue.INACTIVE, RdapStatus.INACTIVE)
          .put(StatusValue.LINKED, RdapStatus.ASSOCIATED)
          .put(StatusValue.OK, RdapStatus.ACTIVE)
          .put(StatusValue.PENDING_CREATE, RdapStatus.PENDING_CREATE)
          .put(StatusValue.PENDING_DELETE, RdapStatus.PENDING_DELETE)
          // RdapStatus.PENDING_RENEW not defined in our system
          // RdapStatus.PENDING_RESTORE not defined in our system
          .put(StatusValue.PENDING_TRANSFER, RdapStatus.PENDING_TRANSFER)
          .put(StatusValue.PENDING_UPDATE, RdapStatus.PENDING_UPDATE)
          // RdapStatus.REDEMPTION_PERIOD not defined in our system
          // RdapStatus.RENEW_PERIOD not defined in our system
          .put(StatusValue.SERVER_DELETE_PROHIBITED, RdapStatus.SERVER_DELETE_PROHIBITED)
          .put(StatusValue.SERVER_HOLD, RdapStatus.SERVER_HOLD)
          .put(StatusValue.SERVER_RENEW_PROHIBITED, RdapStatus.SERVER_RENEW_PROHIBITED)
          .put(StatusValue.SERVER_TRANSFER_PROHIBITED, RdapStatus.SERVER_TRANSFER_PROHIBITED)
          .put(StatusValue.SERVER_UPDATE_PROHIBITED, RdapStatus.SERVER_UPDATE_PROHIBITED)
          // RdapStatus.TRANSFER_PERIOD not defined in our system
          .build();

  /**
   * Map of EPP event values to the RDAP equivalents.
   *
   * <p>Only has entries for optional events, either stated as optional in the RDAP Response Profile
   * 15feb19, or not mentioned at all but thought to be useful anyway.
   *
   * <p>Any required event should be added elsewhere, preferably without using HistoryEntries (so
   * that we don't need to load HistoryEntries for "summary" responses).
   */
  private static final ImmutableMap<HistoryEntry.Type, EventAction>
      HISTORY_ENTRY_TYPE_TO_RDAP_EVENT_ACTION_MAP =
          new ImmutableMap.Builder<HistoryEntry.Type, EventAction>()
              .put(HistoryEntry.Type.CONTACT_CREATE, EventAction.REGISTRATION)
              .put(HistoryEntry.Type.CONTACT_DELETE, EventAction.DELETION)
              .put(HistoryEntry.Type.CONTACT_TRANSFER_APPROVE, EventAction.TRANSFER)

              /** Not in the Response Profile. */
              .put(HistoryEntry.Type.DOMAIN_AUTORENEW, EventAction.REREGISTRATION)
              /** Not in the Response Profile. */
              .put(HistoryEntry.Type.DOMAIN_DELETE, EventAction.DELETION)
              /** Not in the Response Profile. */
              .put(HistoryEntry.Type.DOMAIN_RENEW, EventAction.REREGISTRATION)
              /** Not in the Response Profile. */
              .put(HistoryEntry.Type.DOMAIN_RESTORE, EventAction.REINSTANTIATION)
              /** Section 2.3.2.3, optional. */
              .put(HistoryEntry.Type.DOMAIN_TRANSFER_APPROVE, EventAction.TRANSFER)
              .put(HistoryEntry.Type.HOST_CREATE, EventAction.REGISTRATION)
              .put(HistoryEntry.Type.HOST_DELETE, EventAction.DELETION)
              .build();

  private static final ImmutableList<RdapStatus> STATUS_LIST_ACTIVE =
      ImmutableList.of(RdapStatus.ACTIVE);
  private static final ImmutableList<RdapStatus> STATUS_LIST_INACTIVE =
      ImmutableList.of(RdapStatus.INACTIVE);
  private static final ImmutableMap<String, ImmutableList<String>> PHONE_TYPE_VOICE =
      ImmutableMap.of("type", ImmutableList.of("voice"));
  private static final ImmutableMap<String, ImmutableList<String>> PHONE_TYPE_FAX =
      ImmutableMap.of("type", ImmutableList.of("fax"));

  /** Sets the ordering for hosts; just use the fully qualified host name. */
  private static final Ordering<HostResource> HOST_RESOURCE_ORDERING =
      Ordering.natural().onResultOf(HostResource::getHostName);

  /** Sets the ordering for designated contacts; order them in a fixed order by contact type. */
  private static final Ordering<DesignatedContact> DESIGNATED_CONTACT_ORDERING =
      Ordering.natural().onResultOf(DesignatedContact::getType);

  /** Creates the TOS notice that is added to every reply. */
  Notice createTosNotice() {
    String linkValue = makeRdapServletRelativeUrl("help", RdapHelpAction.TOS_PATH);
    Link selfLink =
        Link.builder().setRel("self").setHref(linkValue).setType("application/rdap+json").build();

    Notice.Builder noticeBuilder =
        Notice.builder()
            .setTitle("RDAP Terms of Service")
            .setDescription(rdapTos)
            .addLink(selfLink);
    if (rdapTosStaticUrl != null) {
      URI htmlBaseURI = URI.create(fullServletPath);
      URI htmlUri = htmlBaseURI.resolve(rdapTosStaticUrl);
      noticeBuilder.addLink(
          Link.builder()
              .setRel("alternate")
              .setHref(htmlUri.toString())
              .setType("text/html")
              .build());
    }
    return noticeBuilder.build();
  }

  /**
   * Creates a JSON object for a {@link DomainBase}.
   *
   * <p>NOTE that domain searches aren't in the spec yet - they're in the RFC 9082 that describes
   * the query format, but they aren't in the RDAP Technical Implementation Guide 15feb19, meaning
   * we don't have to implement them yet and the RDAP Response Profile doesn't apply to them.
   *
   * <p>We're implementing domain searches anyway, BUT we won't have the response for searches
   * conform to the RDAP Response Profile.
   *
   * @param domainBase the domain resource object from which the JSON object should be created
   * @param outputDataType whether to generate FULL or SUMMARY data. Domains are never INTERNAL.
   */
  RdapDomain createRdapDomain(DomainBase domainBase, OutputDataType outputDataType) {
    RdapDomain.Builder builder = RdapDomain.builder();
    builder.linksBuilder().add(makeSelfLink("domain", domainBase.getDomainName()));
    if (outputDataType != OutputDataType.FULL) {
      builder.remarksBuilder().add(RdapIcannStandardInformation.SUMMARY_DATA_REMARK);
    }
    // RDAP Response Profile 15feb19 section 2.1 discusses the domain name.
    builder.setLdhName(domainBase.getDomainName());
    // RDAP Response Profile 15feb19 section 2.2:
    // The domain handle MUST be the ROID
    builder.setHandle(domainBase.getRepoId());
    // If this is a summary (search result) - we'll return now. Since there's no requirement for
    // domain searches at all, having the name, handle, and self link is enough.
    if (outputDataType == OutputDataType.SUMMARY) {
      return builder.build();
    }
    // RDAP Response Profile 15feb19 section 2.3.1:
    // The domain object in the RDAP response MUST contain the following events:
    // [registration, expiration, last update of RDAP database]
    builder
        .eventsBuilder()
        .add(
            Event.builder()
                .setEventAction(EventAction.REGISTRATION)
                .setEventActor(
                    Optional.ofNullable(domainBase.getCreationClientId()).orElse("(none)"))
                .setEventDate(domainBase.getCreationTime())
                .build(),
            Event.builder()
                .setEventAction(EventAction.EXPIRATION)
                .setEventDate(domainBase.getRegistrationExpirationTime())
                .build(),
            Event.builder()
                .setEventAction(EventAction.LAST_UPDATE_OF_RDAP_DATABASE)
                .setEventDate(getRequestTime())
                .build());
    // RDAP Response Profile 15feb19 section 2.3.2 discusses optional events. We add some of those
    // here. We also add a few others we find interesting.
    builder.eventsBuilder().addAll(makeOptionalEvents(domainBase));
    // RDAP Response Profile 15feb19 section 2.4.1:
    // The domain object in the RDAP response MUST contain an entity with the Registrar role.
    //
    // See {@link createRdapRegistrarEntity} for details of section 2.4 conformance
    Registrar registrar =
        Registrar.loadRequiredRegistrarCached(domainBase.getCurrentSponsorClientId());
    builder.entitiesBuilder().add(createRdapRegistrarEntity(registrar, OutputDataType.INTERNAL));
    // RDAP Technical Implementation Guide 3.2: must have link to the registrar's RDAP URL for this
    // domain, with rel=related.
    for (String registrarRdapBase : registrar.getRdapBaseUrls()) {
      String href = makeServerRelativeUrl(registrarRdapBase, "domain", domainBase.getDomainName());
      builder
          .linksBuilder()
          .add(
              Link.builder()
                  .setHref(href)
                  .setRel("related")
                  .setType("application/rdap+json")
                  .build());
    }
    // RDAP Response Profile 2.6.1: must have at least one status member
    // makeStatusValueList should in theory always contain one of either "active" or "inactive".
    ImmutableSet<RdapStatus> status =
        makeStatusValueList(
            domainBase.getStatusValues(),
            false, // isRedacted
            domainBase.getDeletionTime().isBefore(getRequestTime()));
    builder.statusBuilder().addAll(status);
    if (status.isEmpty()) {
      logger.atWarning().log(
          "Domain %s (ROID %s) doesn't have any status",
          domainBase.getDomainName(), domainBase.getRepoId());
    }
    // RDAP Response Profile 2.6.3, must have a notice about statuses. That is in {@link
    // RdapIcannStandardInformation#domainBoilerplateNotices}

    // Kick off the database loads of the nameservers that we will need, so it can load
    // asynchronously while we load and process the contacts.
    ImmutableSet<HostResource> loadedHosts =
        transactIfJpaTm(
            () -> ImmutableSet.copyOf(tm().loadByKeys(domainBase.getNameservers()).values()));
    // Load the registrant and other contacts and add them to the data.
    ImmutableMap<VKey<? extends ContactResource>, ContactResource> loadedContacts =
        transactIfJpaTm(() -> tm().loadByKeysIfPresent(domainBase.getReferencedContacts()));
    // RDAP Response Profile 2.7.3, A domain MUST have the REGISTRANT, ADMIN, TECH roles and MAY
    // have others. We also add the BILLING.
    //
    // RDAP Response Profile 2.7.1, 2.7.3 - we MUST have the contacts. 2.7.4 discusses redaction of
    // fields we don't want to show (as opposed to not having contacts at all) because of GDPR etc.
    //
    // the GDPR redaction is handled in createRdapContactEntity
    ImmutableSetMultimap<VKey<ContactResource>, Type> contactsToRoles =
        Streams.concat(
                domainBase.getContacts().stream(),
                Stream.of(DesignatedContact.create(Type.REGISTRANT, domainBase.getRegistrant())))
            .sorted(DESIGNATED_CONTACT_ORDERING)
            .collect(
                toImmutableSetMultimap(
                    DesignatedContact::getContactKey, DesignatedContact::getType));

    for (VKey<ContactResource> contactKey : contactsToRoles.keySet()) {
      Set<RdapEntity.Role> roles =
          contactsToRoles.get(contactKey).stream()
              .map(RdapJsonFormatter::convertContactTypeToRdapRole)
              .collect(toImmutableSet());
      if (roles.isEmpty()) {
        continue;
      }
      builder
          .entitiesBuilder()
          .add(
              createRdapContactEntity(
                  loadedContacts.get(contactKey), roles, OutputDataType.INTERNAL));
    }
    // Add the nameservers to the data; the load was kicked off above for efficiency.
    // RDAP Response Profile 2.9: we MUST have the nameservers
    for (HostResource hostResource : HOST_RESOURCE_ORDERING.immutableSortedCopy(loadedHosts)) {
      builder.nameserversBuilder().add(createRdapNameserver(hostResource, OutputDataType.INTERNAL));
    }

    // RDAP Response Profile 2.10 - MUST contain a secureDns member including at least a
    // delegationSigned element. Other elements (e.g. dsData) MUST be included if the domain name is
    // signed and the elements are stored in the Registry
    //
    // TODO(b/133310221): get the zoneSigned value from the config files.
    SecureDns.Builder secureDnsBuilder = SecureDns.builder().setZoneSigned(true);
    domainBase.getDsData().forEach(secureDnsBuilder::addDsData);
    builder.setSecureDns(secureDnsBuilder.build());

    return builder.build();
  }

  /**
   * Creates a JSON object for a {@link HostResource}.
   *
   * @param hostResource the host resource object from which the JSON object should be created
   * @param outputDataType whether to generate full or summary data
   */
  RdapNameserver createRdapNameserver(HostResource hostResource, OutputDataType outputDataType) {
    RdapNameserver.Builder builder = RdapNameserver.builder();
    builder.linksBuilder().add(makeSelfLink("nameserver", hostResource.getHostName()));
    if (outputDataType != OutputDataType.FULL) {
      builder.remarksBuilder().add(RdapIcannStandardInformation.SUMMARY_DATA_REMARK);
    }

    // We need the ldhName: RDAP Response Profile 2.9.1, 4.1
    builder.setLdhName(hostResource.getHostName());
    // Handle is optional, but if given it MUST be the ROID.
    // We will set it always as it's important as a "self link"
    builder.setHandle(hostResource.getRepoId());

    // Status is optional for internal Nameservers - RDAP Response Profile 2.9.2
    // It isn't mentioned at all anywhere else. So we can just not put it at all?
    //
    // To be safe, we'll put it on the "FULL" version anyway
    if (outputDataType == OutputDataType.FULL) {
      ImmutableSet.Builder<StatusValue> statuses = new ImmutableSet.Builder<>();
      statuses.addAll(hostResource.getStatusValues());
      if (isLinked(hostResource.createVKey(), getRequestTime())) {
        statuses.add(StatusValue.LINKED);
      }
      if (hostResource.isSubordinate()
          && transactIfJpaTm(
              () ->
                  tm().loadByKey(hostResource.getSuperordinateDomain())
                      .cloneProjectedAtTime(getRequestTime())
                      .getStatusValues()
                      .contains(StatusValue.PENDING_TRANSFER))) {
        statuses.add(StatusValue.PENDING_TRANSFER);
      }
      builder
          .statusBuilder()
          .addAll(
              makeStatusValueList(
                  statuses.build(),
                  false, // isRedacted
                  hostResource.getDeletionTime().isBefore(getRequestTime())));
    }

    // For query responses - we MUST have all the ip addresses: RDAP Response Profile 4.2.
    //
    // However, it is optional for internal responses: RDAP Response Profile 2.9.2
    if (outputDataType != OutputDataType.INTERNAL) {
      for (InetAddress inetAddress : hostResource.getInetAddresses()) {
        if (inetAddress instanceof Inet4Address) {
          builder.ipv4Builder().add(InetAddresses.toAddrString(inetAddress));
        } else if (inetAddress instanceof Inet6Address) {
          builder.ipv6Builder().add(InetAddresses.toAddrString(inetAddress));
        }
      }
    }

    // RDAP Response Profile 4.3 - Registrar member is optional, so we only set it for FULL
    if (outputDataType == OutputDataType.FULL) {
      Registrar registrar =
          Registrar.loadRequiredRegistrarCached(hostResource.getPersistedCurrentSponsorClientId());
      builder.entitiesBuilder().add(createRdapRegistrarEntity(registrar, OutputDataType.INTERNAL));
    }
    if (outputDataType != OutputDataType.INTERNAL) {
      // Rdap Response Profile 4.4, must have "last update of RDAP database" response. But this is
      // only for direct query responses and not for internal objects.
      builder.setLastUpdateOfRdapDatabaseEvent(
          Event.builder()
              .setEventAction(EventAction.LAST_UPDATE_OF_RDAP_DATABASE)
              .setEventDate(getRequestTime())
              .build());
    }
    return builder.build();
  }

  /**
   * Creates a JSON object for a {@link ContactResource} and associated contact type.
   *
   * @param contactResource the contact resource object from which the JSON object should be created
   * @param roles the roles of this contact
   * @param outputDataType whether to generate full or summary data
   */
  RdapContactEntity createRdapContactEntity(
      ContactResource contactResource,
      Iterable<RdapEntity.Role> roles,
      OutputDataType outputDataType) {
    RdapContactEntity.Builder contactBuilder = RdapContactEntity.builder();

    // RDAP Response Profile 2.7.1, 2.7.3 - we MUST have the contacts. 2.7.4 discusses censoring of
    // fields we don't want to show (as opposed to not having contacts at all) because of GDPR etc.
    //
    // 2.8 allows for unredacted output for authorized people.
    boolean isAuthorized =
        rdapAuthorization.isAuthorizedForClientId(contactResource.getCurrentSponsorClientId());

    // ROID needs to be redacted if we aren't authorized, so we can't have a self-link for
    // unauthorized users
    if (isAuthorized) {
      contactBuilder.linksBuilder().add(makeSelfLink("entity", contactResource.getRepoId()));
    }

    // Only show the "summary data remark" if the user is authorized to see this data - because
    // unauthorized users don't have a self link meaning they can't navigate to the full data.
    if (outputDataType != OutputDataType.FULL && isAuthorized) {
      contactBuilder.remarksBuilder().add(RdapIcannStandardInformation.SUMMARY_DATA_REMARK);
    }

    // GTLD Registration Data Temp Spec 17may18, Appendix A, 2.3, 2.4 and RDAP Response Profile
    // 2.7.4.1, 2.7.4.2 - the following fields must be redacted:
    // for REGISTRANT:
    // handle (ROID), FN (name), TEL (telephone/fax and extension), street, city, postal code
    // for ADMIN, TECH:
    // handle (ROID), FN (name), TEL (telephone/fax and extension), Organization, street, city,
    // state/province, postal code, country
    //
    // Note that in theory we have to show the Organization and state/province and country for the
    // REGISTRANT. For now we won't do that until we make sure it's really OK for GDPR
    //
    if (!isAuthorized) {
      // RDAP Response Profile 2.7.4.3: if we redact values from the contact, we MUST include a
      // remark
      contactBuilder
          .remarksBuilder()
          .add(RdapIcannStandardInformation.CONTACT_PERSONAL_DATA_HIDDEN_DATA_REMARK);
      // to make sure we don't accidentally display data we shouldn't - we replace the
      // contactResource with a safe resource. Then we can add any information we need (e.g. the
      // Organization / state / country of the registrant), although we currently don't do that.
      contactResource =
          new ContactResource.Builder()
              .setRepoId(CONTACT_REDACTED_VALUE)
              .setVoiceNumber(
                  new ContactPhoneNumber.Builder().setPhoneNumber(CONTACT_REDACTED_VALUE).build())
              .setFaxNumber(
                  new ContactPhoneNumber.Builder().setPhoneNumber(CONTACT_REDACTED_VALUE).build())
              .setInternationalizedPostalInfo(
                  new PostalInfo.Builder()
                      .setName(CONTACT_REDACTED_VALUE)
                      .setOrg(CONTACT_REDACTED_VALUE)
                      .setType(PostalInfo.Type.INTERNATIONALIZED)
                      .setAddress(
                          new ContactAddress.Builder()
                              .setStreet(ImmutableList.of(CONTACT_REDACTED_VALUE))
                              .setCity(CONTACT_REDACTED_VALUE)
                              .setState(CONTACT_REDACTED_VALUE)
                              .setZip(CONTACT_REDACTED_VALUE)
                              .setCountryCode("XX")
                              .build())
                      .build())
              .build();
    }

    // RDAP Response Profile 2.7.3 - we MUST provide a handle set with the ROID, subject to the
    // redaction above.
    contactBuilder.setHandle(contactResource.getRepoId());

    // RDAP Response Profile doesn't mention status for contacts, so we only show it if we're both
    // FULL and Authorized.
    if (outputDataType == OutputDataType.FULL && isAuthorized) {
      contactBuilder
          .statusBuilder()
          .addAll(
              makeStatusValueList(
                  isLinked(contactResource.createVKey(), getRequestTime())
                      ? union(contactResource.getStatusValues(), StatusValue.LINKED)
                      : contactResource.getStatusValues(),
                  false,
                  contactResource.getDeletionTime().isBefore(getRequestTime())));
    }

    contactBuilder.rolesBuilder().addAll(roles);

    VcardArray.Builder vcardBuilder = VcardArray.builder();
    // Adding the VCard members subject to the redaction above.
    //
    // RDAP Response Profile 2.7.3 - we MUST have FN, ADR, TEL, EMAIL.
    //
    // Note that 2.7.5 also says the EMAIL must be omitted, so we'll omit it
    PostalInfo postalInfo = contactResource.getInternationalizedPostalInfo();
    if (postalInfo == null) {
      postalInfo = contactResource.getLocalizedPostalInfo();
    }
    if (postalInfo != null) {
      if (postalInfo.getName() != null) {
        vcardBuilder.add(Vcard.create("fn", "text", postalInfo.getName()));
      }
      if (postalInfo.getOrg() != null) {
        vcardBuilder.add(Vcard.create("org", "text", postalInfo.getOrg()));
      }
      addVCardAddressEntry(vcardBuilder, postalInfo.getAddress());
    }
    ContactPhoneNumber voicePhoneNumber = contactResource.getVoiceNumber();
    if (voicePhoneNumber != null) {
      vcardBuilder.add(makePhoneEntry(PHONE_TYPE_VOICE, makePhoneString(voicePhoneNumber)));
    }
    ContactPhoneNumber faxPhoneNumber = contactResource.getFaxNumber();
    if (faxPhoneNumber != null) {
      vcardBuilder.add(makePhoneEntry(PHONE_TYPE_FAX, makePhoneString(faxPhoneNumber)));
    }
    // RDAP Response Profile 2.7.5.1, 2.7.5.3:
    // email MUST be omitted, and we MUST have a Remark saying so
    contactBuilder
        .remarksBuilder()
        .add(RdapIcannStandardInformation.CONTACT_EMAIL_REDACTED_FOR_DOMAIN);
    contactBuilder.setVcardArray(vcardBuilder.build());

    if (outputDataType != OutputDataType.INTERNAL) {
      // Rdap Response Profile 2.7.6 must have "last update of RDAP database" response. But this is
      // only for direct query responses and not for internal objects. I'm not sure why it's in that
      // section at all...
      contactBuilder.setLastUpdateOfRdapDatabaseEvent(
          Event.builder()
              .setEventAction(EventAction.LAST_UPDATE_OF_RDAP_DATABASE)
              .setEventDate(getRequestTime())
              .build());
    }

    // If we are outputting all data (not just summary data), also add events taken from the history
    // entries. This isn't strictly required.
    //
    // We also only add it for authorized users because millisecond times can fingerprint a user
    // just as much as the handle can.
    if (outputDataType == OutputDataType.FULL && isAuthorized) {
      contactBuilder.eventsBuilder().addAll(makeOptionalEvents(contactResource));
    }
    return contactBuilder.build();
  }

  /**
   * Creates a JSON object for a {@link Registrar}.
   *
   * <p>This object can be INTERNAL to the Domain and Nameserver responses, with requirements
   * discussed in the RDAP Response Profile 15feb19 sections 2.4 (internal to Domain) and 4.3
   * (internal to Namesever)
   *
   * @param registrar the registrar object from which the RDAP response
   * @param outputDataType whether to generate FULL, SUMMARY, or INTERNAL data.
   */
  RdapRegistrarEntity createRdapRegistrarEntity(
      Registrar registrar, OutputDataType outputDataType) {
    RdapRegistrarEntity.Builder builder = RdapRegistrarEntity.builder();
    if (outputDataType != OutputDataType.FULL) {
      builder.remarksBuilder().add(RdapIcannStandardInformation.SUMMARY_DATA_REMARK);
    }
    // Create the vCard.
    VcardArray.Builder vcardBuilder = VcardArray.builder();
    // Rdap Response Profile 2.4.1, 3.1 - The role must me "registrar" and a valid FN VCard must be
    // present (3.1 requires additional VCards, which will be added next)
    builder.rolesBuilder().add(RdapEntity.Role.REGISTRAR);
    String registrarName = registrar.getRegistrarName();
    vcardBuilder.add(Vcard.create("fn", "text", registrarName == null ? "(none)" : registrarName));

    // Rdap Response Profile 3.1 says the response MUST have valid elements FN (already added), ADR,
    // TEL, EMAIL.
    // Other than FN (that we already added), these aren't required in INTERNAL responses.
    if (outputDataType != OutputDataType.INTERNAL) {
      // Rdap Response Profile 3.1.1 and 3.1.2 discuss the ADR field. See {@link
      // addVcardAddressEntry}
      RegistrarAddress address = registrar.getInternationalizedAddress();
      if (address == null) {
        address = registrar.getLocalizedAddress();
      }
      addVCardAddressEntry(vcardBuilder, address);
      // TEL fields can be phone or fax
      String voicePhoneNumber = registrar.getPhoneNumber();
      if (voicePhoneNumber != null) {
        vcardBuilder.add(makePhoneEntry(PHONE_TYPE_VOICE, "tel:" + voicePhoneNumber));
      }
      String faxPhoneNumber = registrar.getFaxNumber();
      if (faxPhoneNumber != null) {
        vcardBuilder.add(makePhoneEntry(PHONE_TYPE_FAX, "tel:" + faxPhoneNumber));
      }
      // EMAIL field
      String emailAddress = registrar.getEmailAddress();
      if (emailAddress != null) {
        vcardBuilder.add(Vcard.create("email", "text", emailAddress));
      }
    }

    // RDAP Response Profile 2.4.2 and 4.3:
    // The handle MUST be the IANA ID
    // 4.3 also says that if no IANA ID exists (which should never be the case for a valid
    // registrar), the value must be "not applicable". 2.4 doesn't discuss this possibility.
    Long ianaIdentifier = registrar.getIanaIdentifier();
    builder.setHandle((ianaIdentifier == null) ? "not applicable" : ianaIdentifier.toString());
    // RDAP Response Profile 2.4.3 and 4.3:
    // MUST contain a publicId member with the IANA ID
    // 4.3 also says that if no IANA ID exists, the response MUST NOT contain the publicId member.
    // 2.4 doesn't discuss this possibility.
    if (ianaIdentifier != null) {
      builder
          .publicIdsBuilder()
          .add(PublicId.create(PublicId.Type.IANA_REGISTRAR_ID, ianaIdentifier.toString()));
      // We also add a self link if an IANA ID exists
      builder.linksBuilder().add(makeSelfLink("entity", ianaIdentifier.toString()));
    }

    // There's no mention of the registrar STATUS in the RDAP Response Profile, so we'll only add it
    // for FULL response
    // We could probably not add it at all, but it could be useful for us internally
    if (outputDataType == OutputDataType.FULL) {
      builder
          .statusBuilder()
          .addAll(registrar.isLive() ? STATUS_LIST_ACTIVE : STATUS_LIST_INACTIVE);
    }

    builder.setVcardArray(vcardBuilder.build());

    // Registrar contacts are a bit complicated.
    //
    // Rdap Response Profile 3.2, we SHOULD have at least ADMIN and TECH contacts. It says
    // nothing about ABUSE at all.
    //
    // Rdap Response Profile 4.3 doesn't mention contacts at all, meaning probably we don't have to
    // have any contacts there. But the Registrar itself is Optional in that case, so we will just
    // skip it completely.
    //
    // Rdap Response Profile 2.4.5 says the Registrar inside a Domain response MUST include the
    // ABUSE contact, but doesn't require any other contact.
    //
    // In our current Datastore schema, to get the ABUSE contact we must go over all contacts.
    // However, there's something to be said about returning smaller JSON
    //
    // TODO(b/117242274): Need to decide between 2 options:
    // - Write the minimum, meaning only ABUSE for INTERNAL registrars, nothing for SUMMARY (also
    //   saves resources for the RegistrarContact Datastore query!) and everything for FULL.
    // - Write everything for everything.
    //
    // For now we'll do the first.

    if (outputDataType != OutputDataType.SUMMARY) {
      ImmutableList<RdapContactEntity> registrarContacts =
          registrar.getContacts().stream()
              .map(registrarContact -> makeRdapJsonForRegistrarContact(registrarContact))
              .filter(optional -> optional.isPresent())
              .map(optional -> optional.get())
              .filter(
                  contact ->
                      outputDataType == OutputDataType.FULL
                          || contact.roles().contains(RdapEntity.Role.ABUSE))
              .collect(toImmutableList());
      if (registrarContacts.stream()
          .noneMatch(contact -> contact.roles().contains(RdapEntity.Role.ABUSE))) {
        logger.atWarning().log(
            "Registrar '%s' (IANA ID %s) is missing ABUSE contact",
            registrar.getClientId(), registrar.getIanaIdentifier());
      }
      builder.entitiesBuilder().addAll(registrarContacts);
    }

    // Rdap Response Profile 3.3, must have "last update of RDAP database" response. But this is
    // only for direct query responses and not for internal objects.
    if (outputDataType != OutputDataType.INTERNAL) {
      builder.setLastUpdateOfRdapDatabaseEvent(
          Event.builder()
              .setEventAction(EventAction.LAST_UPDATE_OF_RDAP_DATABASE)
              .setEventDate(getRequestTime())
              .build());
    }
    return builder.build();
  }

  /**
   * Creates a JSON object for a {@link RegistrarContact}.
   *
   * <p>Returns empty if this contact shouldn't be visible (doesn't have a role).
   *
   * <p>NOTE that registrar locations in the response require different roles and different VCard
   * members according to the spec. Currently, this function returns all the rolls and all the
   * members for every location, but we might consider refactoring it to allow the minimal required
   * roles and members.
   *
   * <p>Specifically:
   * <li>Registrar inside a Domain only requires the ABUSE role, and only the TEL and EMAIL members
   *     (RDAP Response Profile 2.4.5)
   * <li>Registrar responses to direct query don't require any contact, but *should* have the TECH
   *     and ADMIN roles, but require the FN, TEL and EMAIL members
   * <li>Registrar inside a Nameserver isn't required at all, and if given doesn't require any
   *     contacts
   *
   * @param registrarContact the registrar contact for which the JSON object should be created
   */
  static Optional<RdapContactEntity> makeRdapJsonForRegistrarContact(
      RegistrarContact registrarContact) {
    ImmutableList<RdapEntity.Role> roles = makeRdapRoleList(registrarContact);
    if (roles.isEmpty()) {
      return Optional.empty();
    }
    RdapContactEntity.Builder builder = RdapContactEntity.builder();
    builder.statusBuilder().addAll(STATUS_LIST_ACTIVE);
    builder.rolesBuilder().addAll(roles);
    // Create the vCard.
    VcardArray.Builder vcardBuilder = VcardArray.builder();
    // MUST include FN member: RDAP Response Profile 3.2
    String name = registrarContact.getName();
    if (name != null) {
      vcardBuilder.add(Vcard.create("fn", "text", name));
    }
    // MUST include TEL and EMAIL members: RDAP Response Profile 2.4.5, 3.2
    String voicePhoneNumber = registrarContact.getPhoneNumber();
    if (voicePhoneNumber != null) {
      vcardBuilder.add(makePhoneEntry(PHONE_TYPE_VOICE, "tel:" + voicePhoneNumber));
    }
    String faxPhoneNumber = registrarContact.getFaxNumber();
    if (faxPhoneNumber != null) {
      vcardBuilder.add(makePhoneEntry(PHONE_TYPE_FAX, "tel:" + faxPhoneNumber));
    }
    String emailAddress = registrarContact.getEmailAddress();
    if (emailAddress != null) {
      vcardBuilder.add(Vcard.create("email", "text", emailAddress));
    }
    builder.setVcardArray(vcardBuilder.build());
    return Optional.of(builder.build());
  }

  /** Converts a domain registry contact type into a role as defined by RFC 9083. */
  private static RdapEntity.Role convertContactTypeToRdapRole(DesignatedContact.Type contactType) {
    switch (contactType) {
      case REGISTRANT:
        return RdapEntity.Role.REGISTRANT;
      case TECH:
        return RdapEntity.Role.TECH;
      case BILLING:
        return RdapEntity.Role.BILLING;
      case ADMIN:
        return RdapEntity.Role.ADMIN;
    }
    throw new AssertionError();
  }

  /**
   * Creates the list of RDAP roles for a registrar contact, using the visibleInWhoisAs* flags.
   *
   * <p>Only contacts with a non-empty role list should be visible.
   *
   * <p>The RDAP response profile only mandates the "abuse" entity:
   *
   * <p>2.4.5. Abuse Contact (email, phone) - an RDAP server MUST include an *entity* with the
   * *abuse* role within the registrar *entity* which MUST include *tel* and *email*, and MAY
   * include other members
   *
   * <p>3.2. For direct Registrar queries, we SHOULD have at least "admin" and "tech".
   */
  private static ImmutableList<RdapEntity.Role> makeRdapRoleList(
      RegistrarContact registrarContact) {
    ImmutableList.Builder<RdapEntity.Role> rolesBuilder = new ImmutableList.Builder<>();
    if (registrarContact.getVisibleInWhoisAsAdmin()) {
      rolesBuilder.add(RdapEntity.Role.ADMIN);
    }
    if (registrarContact.getVisibleInWhoisAsTech()) {
      rolesBuilder.add(RdapEntity.Role.TECH);
    }
    if (registrarContact.getVisibleInDomainWhoisAsAbuse()) {
      rolesBuilder.add(RdapEntity.Role.ABUSE);
    }
    return rolesBuilder.build();
  }

  /**
   * Creates the list of optional events to list in domain, nameserver, or contact replies.
   *
   * <p>Only has entries for optional events that won't be shown in "SUMMARY" versions of these
   * objects. These are either stated as optional in the RDAP Response Profile 15feb19, or not
   * mentioned at all but thought to be useful anyway.
   *
   * <p>Any required event should be added elsewhere, preferably without using HistoryEntries (so
   * that we don't need to load HistoryEntries for "summary" responses).
   */
  private ImmutableList<Event> makeOptionalEvents(EppResource resource) {
    HashMap<EventAction, HistoryEntry> lastEntryOfType = Maps.newHashMap();
    // Events (such as transfer, but also create) can appear multiple times. We only want the last
    // time they appeared.
    //
    // We can have multiple create historyEntries if a domain was deleted, and then someone new
    // bought it.
    //
    // From RDAP response profile
    // 2.3.2 The domain object in the RDAP response MAY contain the following events:
    // 2.3.2.3 An event of *eventAction* type *transfer*, with the last date and time that the
    // domain was transferred. The event of *eventAction* type *transfer* MUST be omitted if the
    // domain name has not been transferred since it was created.
    Iterable<? extends HistoryEntry> historyEntries =
        HistoryEntryDao.loadHistoryObjectsForResource(resource.createVKey());
    for (HistoryEntry historyEntry : historyEntries) {
      EventAction rdapEventAction =
          HISTORY_ENTRY_TYPE_TO_RDAP_EVENT_ACTION_MAP.get(historyEntry.getType());
      // Only save the historyEntries if this is a type we care about.
      if (rdapEventAction == null) {
        continue;
      }
      lastEntryOfType.put(rdapEventAction, historyEntry);
    }
    ImmutableList.Builder<Event> eventsBuilder = new ImmutableList.Builder<>();
    DateTime creationTime = resource.getCreationTime();
    DateTime lastChangeTime =
        resource.getLastEppUpdateTime() == null ? creationTime : resource.getLastEppUpdateTime();
    // The order of the elements is stable - it's the order in which the enum elements are defined
    // in EventAction
    for (EventAction rdapEventAction : EventAction.values()) {
      HistoryEntry historyEntry = lastEntryOfType.get(rdapEventAction);
      // Check if there was any entry of this type
      if (historyEntry == null) {
        continue;
      }
      DateTime modificationTime = historyEntry.getModificationTime();
      // We will ignore all events that happened before the "creation time", since these events are
      // from a "previous incarnation of the domain" (for a domain that was owned by someone,
      // deleted, and then bought by someone else)
      if (modificationTime.isBefore(creationTime)) {
        continue;
      }
      eventsBuilder.add(
          Event.builder()
              .setEventAction(rdapEventAction)
              .setEventActor(historyEntry.getClientId())
              .setEventDate(modificationTime)
              .build());
      // The last change time might not be the lastEppUpdateTime, since some changes happen without
      // any EPP update (for example, by the passage of time).
      if (modificationTime.isAfter(lastChangeTime) && modificationTime.isBefore(getRequestTime())) {
        lastChangeTime = modificationTime;
      }
    }
    // RDAP Response Profile 15feb19 section 2.3.2.2:
    // The event of eventAction type last changed MUST be omitted if the domain name has not been
    // updated since it was created
    if (lastChangeTime.isAfter(creationTime)) {
      eventsBuilder.add(makeEvent(EventAction.LAST_CHANGED, null, lastChangeTime));
    }
    return eventsBuilder.build();
  }

  /** Creates an RDAP event object as defined by RFC 9083. */
  private static Event makeEvent(
      EventAction eventAction, @Nullable String eventActor, DateTime eventDate) {
    Event.Builder builder = Event.builder()
        .setEventAction(eventAction)
        .setEventDate(eventDate);
    if (eventActor != null) {
      builder.setEventActor(eventActor);
    }
    return builder.build();
  }

  /**
   * Creates a vCard address entry: array of strings specifying the components of the address.
   *
   * <p>Rdap Response Profile 3.1.1: MUST contain the following fields: Street, City, Country Rdap
   * Response Profile 3.1.2: optional fields: State/Province, Postal Code, Fax Number
   *
   * @see <a href="https://tools.ietf.org/html/rfc7095">RFC 7095: jCard: The JSON Format for
   *     vCard</a>
   */
  private static void addVCardAddressEntry(VcardArray.Builder vcardArrayBuilder, Address address) {
    if (address == null) {
      return;
    }
    JsonArray addressArray = new JsonArray();
    addressArray.add(""); // PO box
    addressArray.add(""); // extended address

    // The vCard spec allows several different ways to handle multiline street addresses. Per
    // Gustavo Lozano of ICANN, the one we should use is an embedded array of street address lines
    // if there is more than one line:
    //
    //   RFC7095 provides two examples of structured addresses, and one of the examples shows a
    //   street JSON element that contains several data elements. The example showing (see below)
    //   several data elements is the expected output when two or more <contact:street> elements
    //   exists in the contact object.
    //
    //   ["adr", {}, "text",
    //    [
    //    "", "",
    //    ["My Street", "Left Side", "Second Shack"],
    //    "Hometown", "PA", "18252", "U.S.A."
    //    ]
    //   ]
    //
    // Gustavo further clarified that the embedded array should only be used if there is more than
    // one line:
    //
    //   My reading of RFC 7095 is that if only one element is known, it must be a string. If
    //   multiple elements are known (e.g. two or three street elements were provided in the case of
    //   the EPP contact data model), an array must be used.
    //
    //   I don’t think that one street address line nested in a single-element array is valid
    //   according to RFC 7095.
    ImmutableList<String> street = address.getStreet();
    if (street.isEmpty()) {
      addressArray.add("");
    } else if (street.size() == 1) {
      addressArray.add(street.get(0));
    } else {
      JsonArray streetArray = new JsonArray();
      street.forEach(streetArray::add);
      addressArray.add(streetArray);
    }
    addressArray.add(nullToEmpty(address.getCity()));
    addressArray.add(nullToEmpty(address.getState()));
    addressArray.add(nullToEmpty(address.getZip()));
    addressArray.add(
        new Locale("en", nullToEmpty(address.getCountryCode()))
            .getDisplayCountry(new Locale("en")));
    vcardArrayBuilder.add(Vcard.create(
        "adr",
        "text",
        addressArray));
  }

  /** Creates a vCard phone number entry. */
  private static Vcard makePhoneEntry(
      ImmutableMap<String, ImmutableList<String>> type, String phoneNumber) {

    return Vcard.create("tel", type, "uri", phoneNumber);
  }

  /** Creates a phone string in URI format, as per the vCard spec. */
  private static String makePhoneString(ContactPhoneNumber phoneNumber) {
    String phoneString = String.format("tel:%s", phoneNumber.getPhoneNumber());
    if (phoneNumber.getExtension() != null) {
      phoneString = phoneString + ";ext=" + phoneNumber.getExtension();
    }
    return phoneString;
  }

  /**
   * Creates a string array of status values.
   *
   * <p>The spec indicates that OK should be listed as "active". We use the "inactive" status to
   * indicate deleted objects, and as directed by the profile, the "removed" status to indicate
   * redacted objects.
   */
  private static ImmutableSet<RdapStatus> makeStatusValueList(
      ImmutableSet<StatusValue> statusValues, boolean isRedacted, boolean isDeleted) {
    Stream<RdapStatus> stream =
        statusValues
            .stream()
            .map(status -> STATUS_TO_RDAP_STATUS_MAP.getOrDefault(status, RdapStatus.OBSCURED));
    if (isRedacted) {
      stream = Streams.concat(stream, Stream.of(RdapStatus.REMOVED));
    }
    if (isDeleted) {
      stream =
          Streams.concat(
              stream.filter(not(RdapStatus.ACTIVE::equals)),
              Stream.of(RdapStatus.INACTIVE));
    }
    return stream
        .sorted(Ordering.natural().onResultOf(RdapStatus::getDisplayName))
        .collect(toImmutableSet());
  }

  /**
   * Create a link relative to the RDAP server endpoint.
   */
  String makeRdapServletRelativeUrl(String part, String... moreParts) {
    return makeServerRelativeUrl(fullServletPath, part, moreParts);
  }

  /**
   * Create a link relative to some base server
   */
  static String makeServerRelativeUrl(String baseServer, String part, String... moreParts) {
    String relativePath = Paths.get(part, moreParts).toString();
    if (baseServer.endsWith("/")) {
      return baseServer + relativePath;
    }
    return baseServer + "/" + relativePath;
  }

  /**
   * Creates a self link as directed by the spec.
   *
   * @see <a href="https://tools.ietf.org/html/rfc9083">RFC 9083: JSON Responses for the
   *     Registration Data Access Protocol (RDAP)</a>
   */
  private Link makeSelfLink(String type, String name) {
    String url = makeRdapServletRelativeUrl(type, name);
    return Link.builder()
        .setRel("self")
        .setHref(url)
        .setType("application/rdap+json")
        .build();
  }

  /**
   * Returns the DateTime this request took place.
   *
   * <p>The RDAP reply is large with a lot of different object in them. We want to make sure that
   * all these objects are projected to the same "now".
   *
   * <p>This "now" will also be considered the time of the "last update of RDAP database" event that
   * RDAP sepc requires.
   *
   * <p>We would have set this during the constructor, but the clock is injected after construction.
   * So instead we set the time during the first call to this function.
   *
   * <p>We would like even more to just inject it in RequestModule and use it in many places in our
   * codebase that just need a general "now" of the request, but that's a lot of work.
   */
  DateTime getRequestTime() {
    if (requestTime == null) {
      requestTime = clock.nowUtc();
    }
    return requestTime;
  }
}
