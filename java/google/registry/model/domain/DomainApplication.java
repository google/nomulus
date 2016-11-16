// Copyright 2016 The Nomulus Authors. All Rights Reserved.
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

package google.registry.model.domain;

import static google.registry.model.ofy.ObjectifyService.ofy;
import static google.registry.model.ofy.Ofy.RECOMMENDED_MEMCACHE_EXPIRATION;
import static google.registry.util.CollectionUtils.nullToEmptyImmutableCopy;

import com.google.common.collect.ImmutableList;
import com.googlecode.objectify.annotation.Cache;
import com.googlecode.objectify.annotation.EntitySubclass;
import com.googlecode.objectify.annotation.OnLoad;
import google.registry.model.annotations.ExternalMessagingName;
import google.registry.model.domain.launch.ApplicationStatus;
import google.registry.model.domain.launch.LaunchPhase;
import google.registry.model.eppcommon.Trid;
import google.registry.model.eppinput.EppInput;
import google.registry.model.eppinput.EppInput.ResourceCommandWrapper;
import google.registry.model.reporting.HistoryEntry;
import google.registry.model.smd.EncodedSignedMark;
import google.registry.xml.XmlTransformer;
import java.io.ByteArrayInputStream;
import java.util.List;
import javax.xml.bind.annotation.XmlRootElement;
import javax.xml.bind.annotation.XmlTransient;
import javax.xml.bind.annotation.XmlType;
import org.joda.money.Money;
import org.joda.time.DateTime;

/** An application to create a domain. */
@XmlRootElement(name = "infData")
@XmlType(propOrder = {
    "fullyQualifiedDomainName",
    "repoId",
    "status",
    "marshalledRegistrant",
    "marshalledContacts",
    "marshalledNameservers",
    "currentSponsorClientId",
    "creationClientId",
    "creationTime",
    "lastEppUpdateClientId",
    "lastEppUpdateTime",
    "authInfo"})
@Cache(expirationSeconds = RECOMMENDED_MEMCACHE_EXPIRATION)
@EntitySubclass(index = true)
@ExternalMessagingName("application")
public class DomainApplication extends DomainBase {

  /**
   * The transaction id of the EPP command that created this application. This is saved off so that
   * we can generate the poll message communicating the application result once it is rejected or
   * allocated.
   *
   * <p>This field may be null for applications that were created before the field was added.
   */
  @XmlTransient
  Trid creationTrid;

  /**
   * The phase which this application is registered for. We store this only so we can return it back
   * to the user on info commands.
   */
  @XmlTransient
  LaunchPhase phase;

  /** The requested registration period. */
  @XmlTransient
  Period period;

  /** The current status of this application. */
  @XmlTransient
  ApplicationStatus applicationStatus;

  /** The encoded signed marks which were asserted when this application was created. */
  @XmlTransient
  List<EncodedSignedMark> encodedSignedMarks;

  /** The amount paid at auction for the right to register the domain. Used only for reporting. */
  @XmlTransient
  Money auctionPrice;

  // TODO(b/32447342): remove this once the period has been populated on all DomainApplications
  @OnLoad
  void setYears() {
    if (period == null) {
      // Extract the registration period from the XML used to create the domain application.
      try {
        HistoryEntry history = ofy().load()
            .type(HistoryEntry.class)
            .ancestor(this)
            .order("modificationTime")
            .first()
            .now();
        if (history != null) {
          byte[] xmlBytes = history.getXmlBytes();
          EppInput eppInput = unmarshal(EppInput.class, xmlBytes);
          period = ((DomainCommand.Create)
              ((ResourceCommandWrapper) eppInput.getCommandWrapper().getCommand())
                  .getResourceCommand()).getPeriod();
        }
      } catch (Exception e) {
        // If we encounter an exception, give up on this defaulting.
      }
    }
  }

  /**
   * Unmarshal bytes into Epp classes.
   *
   * <p>This method, and the things it depends on, are copied from EppXmlTransformer, because that
   * class is in the flows directory, and we don't want a build dependency of model on build. It can
   * be removed when the @OnLoad method is removed.
   *
   * @param clazz type to return, specified as a param to enforce typesafe generics
   * @see "http://errorprone.info/bugpattern/TypeParameterUnusedInFormals"
   */
  private static <T> T unmarshal(Class<T> clazz, byte[] bytes) throws Exception {
    return INPUT_TRANSFORMER.unmarshal(clazz, new ByteArrayInputStream(bytes));
  }

  // Hardcoded XML schemas, ordered with respect to dependency.
  private static final ImmutableList<String> SCHEMAS = ImmutableList.of(
      "eppcom.xsd",
      "epp.xsd",
      "contact.xsd",
      "host.xsd",
      "domain.xsd",
      "rgp.xsd",
      "secdns.xsd",
      "fee06.xsd",
      "fee11.xsd",
      "fee12.xsd",
      "metadata.xsd",
      "mark.xsd",
      "dsig.xsd",
      "smd.xsd",
      "launch.xsd",
      "allocate.xsd",
      "flags.xsd");

  private static final XmlTransformer INPUT_TRANSFORMER =
      new XmlTransformer(SCHEMAS, EppInput.class);

  // End of copied stuff used by @OnLoad method

  @Override
  public String getFullyQualifiedDomainName() {
    return fullyQualifiedDomainName;
  }

  public Trid getCreationTrid() {
    return creationTrid;
  }

  public LaunchPhase getPhase() {
    return phase;
  }

  public Period getPeriod() {
    return period;
  }

  public ApplicationStatus getApplicationStatus() {
    return applicationStatus;
  }

  public ImmutableList<EncodedSignedMark> getEncodedSignedMarks() {
    return nullToEmptyImmutableCopy(encodedSignedMarks);
  }

  public Money getAuctionPrice() {
    return auctionPrice;
  }

  /**
   * The application id is the repoId.
   */
  @Override
  public String getForeignKey() {
    return getRepoId();
  }

  @Override
  public DomainApplication cloneProjectedAtTime(DateTime now) {
    // Applications have no grace periods and can't be transferred, so there is nothing to project.
    return this;
  }

  @Override
  public Builder asBuilder() {
    return new Builder(clone(this));
  }

  /** A builder for constructing {@link DomainApplication}, since it is immutable. */
  public static class Builder extends DomainBase.Builder<DomainApplication, Builder> {

    public Builder() {}

    private Builder(DomainApplication instance) {
      super(instance);
    }

    public Builder setCreationTrid(Trid creationTrid) {
      getInstance().creationTrid = creationTrid;
      return this;
    }

    public Builder setPhase(LaunchPhase phase) {
      getInstance().phase = phase;
      return this;
    }

    public Builder setPeriod(Period period) {
      getInstance().period = period;
      return this;
    }

    public Builder setApplicationStatus(ApplicationStatus applicationStatus) {
      getInstance().applicationStatus = applicationStatus;
      return this;
    }

    public Builder setEncodedSignedMarks(ImmutableList<EncodedSignedMark> encodedSignedMarks) {
      getInstance().encodedSignedMarks = encodedSignedMarks;
      return this;
    }

    public Builder setAuctionPrice(Money auctionPrice) {
      getInstance().auctionPrice = auctionPrice;
      return this;
    }
  }
}
