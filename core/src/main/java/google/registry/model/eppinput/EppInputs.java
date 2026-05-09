// Copyright 2026 The Nomulus Authors. All Rights Reserved.
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

package google.registry.model.eppinput;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import google.registry.model.domain.DomainAuthInfo;
import google.registry.model.domain.DomainCommand;
import google.registry.model.domain.ForeignKeyedDesignatedContact;
import google.registry.model.domain.ForeignKeyedDesignatedContact.Type;
import google.registry.model.domain.Period;
import google.registry.model.domain.secdns.SecDnsUpdateExtension;
import google.registry.model.eppcommon.AuthInfo.PasswordAuth;
import google.registry.model.eppcommon.StatusValue;
import google.registry.model.eppinput.EppInput.CommandExtension;
import google.registry.model.host.HostCommand;
import java.net.InetAddress;
import java.time.LocalDate;
import javax.annotation.Nullable;

/** Fluent DSL for creating {@link EppInput} objects. */
public class EppInputs {

  /** Returns a builder for a domain create command with the specified name and password. */
  public static DomainCreateBuilder createDomain(String domainName, String password) {
    return new DomainCreateBuilder(domainName, password);
  }

  /** Returns a builder for a domain update command for the specified domain name. */
  public static DomainUpdateBuilder updateDomain(String domainName) {
    return new DomainUpdateBuilder(domainName);
  }

  /**
   * Returns a builder for a domain renew command for the specified domain, extending it by the
   * given number of years.
   */
  public static DomainRenewBuilder renewDomain(String domainName, int years, LocalDate expDate) {
    return new DomainRenewBuilder(domainName, years, expDate);
  }

  /** Returns a builder for a domain delete command for the specified domain name. */
  public static DomainDeleteBuilder deleteDomain(String domainName) {
    return new DomainDeleteBuilder(domainName);
  }

  /** Returns a builder for a host create command with the specified host name. */
  public static HostCreateBuilder createHost(String hostName) {
    return new HostCreateBuilder(hostName);
  }

  /** Returns a builder for a host delete command for the specified host name. */
  public static HostDeleteBuilder deleteHost(String hostName) {
    return new HostDeleteBuilder(hostName);
  }

  /** Returns a builder for a domain check command for the specified list of domain names. */
  public static DomainCheckBuilder checkDomain(ImmutableList<String> domainNames) {
    return new DomainCheckBuilder(domainNames);
  }

  /** Base builder for all fluent EPP commands. */
  public abstract static class FluentEppInputBuilder<T extends FluentEppInputBuilder<T>> {
    protected final EppInput.CommandWrapper.Builder wrapperBuilder =
        new EppInput.CommandWrapper.Builder().setClTrid("RegistryTool");
    protected final ImmutableList.Builder<CommandExtension> extensions =
        new ImmutableList.Builder<>();

    @SuppressWarnings("unchecked")
    protected T self() {
      return (T) this;
    }

    /** Sets the client transaction ID for the command. Defaults to "RegistryTool". */
    public T setClTrid(String clTrid) {
      wrapperBuilder.setClTrid(clTrid);
      return self();
    }

    /** Adds a single EPP command extension. If the extension is null, it is ignored. */
    public T addExtension(@Nullable CommandExtension extension) {
      if (extension != null) {
        extensions.add(extension);
      }
      return self();
    }

    /** Adds multiple EPP command extensions. */
    public T addExtensions(ImmutableList<? extends CommandExtension> extensions) {
      this.extensions.addAll(extensions);
      return self();
    }

    protected abstract EppInput.InnerCommand getCommand();

    /** Builds the {@link EppInput} object. */
    public EppInput build() {
      return new EppInput.Builder()
          .setCommandWrapper(
              wrapperBuilder.setCommand(getCommand()).setExtensions(extensions.build()).build())
          .build();
    }
  }

  /** Fluent builder for domain create commands. */
  public static class DomainCreateBuilder extends FluentEppInputBuilder<DomainCreateBuilder> {
    private final DomainCommand.Create.Builder builder = new DomainCommand.Create.Builder();

    private DomainCreateBuilder(String domainName, String password) {
      builder
          .setDomainName(domainName)
          .setAuthInfo(DomainAuthInfo.create(PasswordAuth.create(password)));
    }

    /** Sets the registration period for the domain. */
    public DomainCreateBuilder setPeriod(int value, Period.Unit unit) {
      builder.setPeriod(Period.create(value, unit));
      return this;
    }

    /** Sets the registrant contact ID for the domain. */
    public DomainCreateBuilder setRegistrant(String contactId) {
      builder.setRegistrant(contactId);
      return this;
    }

    /** Adds the specified contact as both the administrative and technical contact. */
    public DomainCreateBuilder addAdminTechContact(String contactId) {
      builder.setForeignKeyedDesignatedContacts(
          ImmutableSet.of(
              ForeignKeyedDesignatedContact.create(Type.ADMIN, contactId),
              ForeignKeyedDesignatedContact.create(Type.TECH, contactId)));
      return this;
    }

    /** Sets the nameservers for the domain. */
    public DomainCreateBuilder setNameservers(ImmutableSet<String> nameservers) {
      builder.setNameserverHostNames(nameservers);
      return this;
    }

    @Override
    protected EppInput.InnerCommand getCommand() {
      return new EppInput.Create.Builder().setResourceCommand(builder.build()).build();
    }
  }

  /** Fluent builder for domain update commands. */
  public static class DomainUpdateBuilder extends FluentEppInputBuilder<DomainUpdateBuilder> {
    private final DomainCommand.Update.Builder builder = new DomainCommand.Update.Builder();
    private final ImmutableSet.Builder<String> nameserversToAdd = new ImmutableSet.Builder<>();
    private final ImmutableSet.Builder<String> nameserversToRemove = new ImmutableSet.Builder<>();
    private final ImmutableSet.Builder<StatusValue> statusesToAdd = new ImmutableSet.Builder<>();
    private final ImmutableSet.Builder<StatusValue> statusesToRemove = new ImmutableSet.Builder<>();

    private DomainUpdateBuilder(String domainName) {
      builder.setTargetId(domainName);
    }

    /** Adds the specified nameservers to the domain. */
    public DomainUpdateBuilder addNameservers(ImmutableSet<String> nameservers) {
      nameserversToAdd.addAll(nameservers);
      return this;
    }

    /** Removes the specified nameservers from the domain. */
    public DomainUpdateBuilder removeNameservers(ImmutableSet<String> nameservers) {
      nameserversToRemove.addAll(nameservers);
      return this;
    }

    /** Sets the nameservers to the specified set, calculating the diff from the current set. */
    public DomainUpdateBuilder setNameservers(
        ImmutableSet<String> target, ImmutableSet<String> current) {
      return addNameservers(ImmutableSet.copyOf(Sets.difference(target, current)))
          .removeNameservers(ImmutableSet.copyOf(Sets.difference(current, target)));
    }

    /** Adds the specified statuses to the domain. */
    public DomainUpdateBuilder addStatuses(ImmutableSet<StatusValue> statuses) {
      statusesToAdd.addAll(statuses);
      return this;
    }

    /** Removes the specified statuses from the domain. */
    public DomainUpdateBuilder removeStatuses(ImmutableSet<StatusValue> statuses) {
      statusesToRemove.addAll(statuses);
      return this;
    }

    /** Sets the statuses to the specified set, calculating the diff from the current set. */
    public DomainUpdateBuilder setStatuses(
        ImmutableSet<StatusValue> target, ImmutableSet<StatusValue> current) {
      return addStatuses(ImmutableSet.copyOf(Sets.difference(target, current)))
          .removeStatuses(ImmutableSet.copyOf(Sets.difference(current, target)));
    }

    /** Sets a new password for the domain. */
    public DomainUpdateBuilder setNewPassword(String password) {
      builder.setInnerChange(
          new DomainCommand.Update.Change.Builder()
              .setAuthInfo(DomainAuthInfo.create(PasswordAuth.create(password)))
              .build());
      return this;
    }

    /** Sets a DNSSEC update extension for the domain. */
    public DomainUpdateBuilder setSecDnsUpdate(@Nullable SecDnsUpdateExtension secDnsUpdate) {
      return addExtension(secDnsUpdate);
    }

    /** Sets whether the domain should autorenew. This is implemented via a superuser extension. */
    public DomainUpdateBuilder setAutorenews(@Nullable Boolean autorenews) {
      return addExtension(EppExtensions.updateSuperuser(autorenews));
    }

    @Override
    protected EppInput.InnerCommand getCommand() {
      ImmutableSet<String> addNs = nameserversToAdd.build();
      ImmutableSet<StatusValue> addStatus = statusesToAdd.build();
      if (!addNs.isEmpty() || !addStatus.isEmpty()) {
        builder.setInnerAdd(
            new DomainCommand.Update.AddRemove.Builder()
                .setNameserverHostNames(addNs)
                .setStatusValues(addStatus)
                .build());
      }

      ImmutableSet<String> removeNs = nameserversToRemove.build();
      ImmutableSet<StatusValue> removeStatus = statusesToRemove.build();
      if (!removeNs.isEmpty() || !removeStatus.isEmpty()) {
        builder.setInnerRemove(
            new DomainCommand.Update.AddRemove.Builder()
                .setNameserverHostNames(removeNs)
                .setStatusValues(removeStatus)
                .build());
      }

      return new EppInput.Update.Builder().setResourceCommand(builder.build()).build();
    }
  }

  /** Fluent builder for domain renew commands. */
  public static class DomainRenewBuilder extends FluentEppInputBuilder<DomainRenewBuilder> {
    private final DomainCommand.Renew.Builder builder = new DomainCommand.Renew.Builder();

    private DomainRenewBuilder(String domainName, int years, LocalDate expDate) {
      builder
          .setTargetId(domainName)
          .setPeriod(Period.create(years, Period.Unit.YEARS))
          .setCurrentExpirationDate(expDate);
    }

    @Override
    protected EppInput.InnerCommand getCommand() {
      return new EppInput.Renew.Builder().setResourceCommand(builder.build()).build();
    }
  }

  /** Fluent builder for domain delete commands. */
  public static class DomainDeleteBuilder extends FluentEppInputBuilder<DomainDeleteBuilder> {
    private final DomainCommand.Delete builder = new DomainCommand.Delete();

    private DomainDeleteBuilder(String domainName) {
      builder.setTargetId(domainName);
    }

    @Override
    protected EppInput.InnerCommand getCommand() {
      return new EppInput.Delete.Builder().setResourceCommand(builder).build();
    }
  }

  /** Fluent builder for host create commands. */
  public static class HostCreateBuilder extends FluentEppInputBuilder<HostCreateBuilder> {
    private final HostCommand.Create.Builder builder = new HostCommand.Create.Builder();

    private HostCreateBuilder(String hostName) {
      builder.setTargetId(hostName);
    }

    public HostCreateBuilder setInetAddresses(ImmutableSet<InetAddress> inetAddresses) {
      builder.setInetAddresses(inetAddresses);
      return this;
    }

    @Override
    protected EppInput.InnerCommand getCommand() {
      return new EppInput.Create.Builder().setResourceCommand(builder.build()).build();
    }
  }

  /** Fluent builder for host delete commands. */
  public static class HostDeleteBuilder extends FluentEppInputBuilder<HostDeleteBuilder> {
    private final HostCommand.Delete builder = new HostCommand.Delete();

    private HostDeleteBuilder(String hostName) {
      builder.setTargetId(hostName);
    }

    @Override
    protected EppInput.InnerCommand getCommand() {
      return new EppInput.Delete.Builder().setResourceCommand(builder).build();
    }
  }

  /** Fluent builder for domain check commands. */
  public static class DomainCheckBuilder extends FluentEppInputBuilder<DomainCheckBuilder> {
    private final DomainCommand.Check builder = new DomainCommand.Check();

    private DomainCheckBuilder(ImmutableList<String> domainNames) {
      builder.setTargetIds(domainNames);
    }

    @Override
    protected EppInput.InnerCommand getCommand() {
      return new EppInput.Check.Builder().setResourceCommand(builder).build();
    }
  }
}
