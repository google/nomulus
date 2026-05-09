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
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;

/** Static helpers and fluent builders for creating common {@link EppInput} objects for tools. */
public class EppInputs {

  /** Returns a builder for a domain create command. */
  public static DomainCreateBuilder createDomain(String domainName, String password) {
    return new DomainCreateBuilder(domainName, password);
  }

  /** Returns a builder for a domain update command. */
  public static DomainUpdateBuilder updateDomain(String domainName) {
    return new DomainUpdateBuilder(domainName);
  }

  /** Returns a builder for a domain renew command. */
  public static SimpleEppInputBuilder renewDomain(String domainName, int years, LocalDate expDate) {
    DomainCommand.Renew command =
        new DomainCommand.Renew.Builder()
            .setTargetId(domainName)
            .setPeriod(Period.create(years, Period.Unit.YEARS))
            .setCurrentExpirationDate(expDate)
            .build();
    return new SimpleEppInputBuilder(EppInput.Renew.create(command));
  }

  /** Returns a builder for a domain delete command. */
  public static SimpleEppInputBuilder deleteDomain(String domainName) {
    DomainCommand.Delete command = new DomainCommand.Delete();
    command.setTargetId(domainName);
    return new SimpleEppInputBuilder(EppInput.Delete.create(command));
  }

  /** Returns a builder for a host create command. */
  public static HostCreateBuilder createHost(String hostName) {
    return new HostCreateBuilder(hostName);
  }

  /** Returns a builder for a host delete command. */
  public static SimpleEppInputBuilder deleteHost(String hostName) {
    HostCommand.Delete command = new HostCommand.Delete();
    command.setTargetId(hostName);
    return new SimpleEppInputBuilder(EppInput.Delete.create(command));
  }

  /** Returns a builder for a domain check command. */
  public static SimpleEppInputBuilder checkDomain(List<String> domainNames) {
    DomainCommand.Check command = new DomainCommand.Check();
    command.setTargetIds(ImmutableList.copyOf(domainNames));
    return new SimpleEppInputBuilder(EppInput.Check.create(command));
  }

  /** Base class for fluent EPP builders that handle optional extensions. */
  public abstract static class ToolEppInputBuilder<B extends ToolEppInputBuilder<B>> {
    protected final List<CommandExtension> extensions = new ArrayList<>();

    @SuppressWarnings("unchecked")
    protected B self() {
      return (B) this;
    }

    public B addExtension(@Nullable CommandExtension extension) {
      if (extension != null) {
        extensions.add(extension);
      }
      return self();
    }

    public abstract EppInput build();
  }

  /** A simple builder that just wraps a command and allows adding extensions. */
  public static class SimpleEppInputBuilder extends ToolEppInputBuilder<SimpleEppInputBuilder> {
    private final EppInput.InnerCommand command;

    private SimpleEppInputBuilder(EppInput.InnerCommand command) {
      this.command = command;
    }

    @Override
    public EppInput build() {
      return EppInput.create(command, extensions.toArray(new CommandExtension[0]));
    }
  }

  /** Fluent builder for domain create commands. */
  public static class DomainCreateBuilder extends ToolEppInputBuilder<DomainCreateBuilder> {
    private final DomainCommand.Create.Builder builder = new DomainCommand.Create.Builder();

    private DomainCreateBuilder(String domainName, String password) {
      builder
          .setDomainName(domainName)
          .setAuthInfo(DomainAuthInfo.create(PasswordAuth.create(password)));
    }

    public DomainCreateBuilder setPeriod(int value, Period.Unit unit) {
      builder.setPeriod(Period.create(value, unit));
      return this;
    }

    public DomainCreateBuilder setRegistrant(String contactId) {
      builder.setRegistrant(contactId);
      return this;
    }

    public DomainCreateBuilder addAdminTechContact(String contactId) {
      builder.setForeignKeyedDesignatedContacts(
          ImmutableSet.of(
              ForeignKeyedDesignatedContact.create(Type.ADMIN, contactId),
              ForeignKeyedDesignatedContact.create(Type.TECH, contactId)));
      return this;
    }

    public DomainCreateBuilder setNameservers(ImmutableSet<String> nameservers) {
      builder.setNameserverHostNames(nameservers);
      return this;
    }

    @Override
    public EppInput build() {
      return EppInput.create(
          EppInput.Create.create(builder.build()), extensions.toArray(new CommandExtension[0]));
    }
  }

  /** Fluent builder for domain update commands. */
  public static class DomainUpdateBuilder extends ToolEppInputBuilder<DomainUpdateBuilder> {
    private final DomainCommand.Update.Builder builder = new DomainCommand.Update.Builder();
    private final ImmutableSet.Builder<String> nameserversToAdd = new ImmutableSet.Builder<>();
    private final ImmutableSet.Builder<String> nameserversToRemove = new ImmutableSet.Builder<>();
    private final ImmutableSet.Builder<StatusValue> statusesToAdd = new ImmutableSet.Builder<>();
    private final ImmutableSet.Builder<StatusValue> statusesToRemove = new ImmutableSet.Builder<>();

    private DomainUpdateBuilder(String domainName) {
      builder.setTargetId(domainName);
    }

    public DomainUpdateBuilder addNameservers(ImmutableSet<String> nameservers) {
      nameserversToAdd.addAll(nameservers);
      return this;
    }

    public DomainUpdateBuilder removeNameservers(ImmutableSet<String> nameservers) {
      nameserversToRemove.addAll(nameservers);
      return this;
    }

    public DomainUpdateBuilder setNameservers(
        ImmutableSet<String> target, ImmutableSet<String> current) {
      return addNameservers(ImmutableSet.copyOf(Sets.difference(target, current)))
          .removeNameservers(ImmutableSet.copyOf(Sets.difference(current, target)));
    }

    public DomainUpdateBuilder addStatuses(ImmutableSet<StatusValue> statuses) {
      statusesToAdd.addAll(statuses);
      return this;
    }

    /** Adds the specified statuses by their XML names. */
    public DomainUpdateBuilder addStatusesByNames(ImmutableSet<String> statuses) {
      return addStatuses(
          statuses.stream().map(StatusValue::fromXmlName).collect(ImmutableSet.toImmutableSet()));
    }

    public DomainUpdateBuilder removeStatuses(ImmutableSet<StatusValue> statuses) {
      statusesToRemove.addAll(statuses);
      return this;
    }

    /** Removes the specified statuses by their XML names. */
    public DomainUpdateBuilder removeStatusesByNames(ImmutableSet<String> statuses) {
      return removeStatuses(
          statuses.stream().map(StatusValue::fromXmlName).collect(ImmutableSet.toImmutableSet()));
    }

    public DomainUpdateBuilder setStatuses(
        ImmutableSet<StatusValue> target, ImmutableSet<StatusValue> current) {
      return addStatuses(ImmutableSet.copyOf(Sets.difference(target, current)))
          .removeStatuses(ImmutableSet.copyOf(Sets.difference(current, target)));
    }

    public DomainUpdateBuilder setNewPassword(String password) {
      builder.setInnerChange(
          new DomainCommand.Update.Change.Builder()
              .setAuthInfo(DomainAuthInfo.create(PasswordAuth.create(password)))
              .build());
      return this;
    }

    public DomainUpdateBuilder setSecDnsUpdate(@Nullable SecDnsUpdateExtension secDnsUpdate) {
      return addExtension(secDnsUpdate);
    }

    public DomainUpdateBuilder setAutorenews(@Nullable Boolean autorenews) {
      return addExtension(EppExtensions.updateSuperuser(autorenews));
    }

    @Override
    public EppInput build() {
      ImmutableSet<String> addNs = nameserversToAdd.build();
      ImmutableSet<StatusValue> addStatus = statusesToAdd.build();
      if (!addNs.isEmpty() || !addStatus.isEmpty()) {
        builder.setInnerAdd(
            new DomainCommand.Update.DomainAddRemove.Builder()
                .setNameserverHostNames(addNs)
                .setStatusValues(addStatus)
                .build());
      }
      ImmutableSet<String> removeNs = nameserversToRemove.build();
      ImmutableSet<StatusValue> removeStatus = statusesToRemove.build();
      if (!removeNs.isEmpty() || !removeStatus.isEmpty()) {
        builder.setInnerRemove(
            new DomainCommand.Update.DomainAddRemove.Builder()
                .setNameserverHostNames(removeNs)
                .setStatusValues(removeStatus)
                .build());
      }
      return EppInput.create(
          EppInput.Update.create(builder.build()), extensions.toArray(new CommandExtension[0]));
    }
  }

  /** Fluent builder for host create commands. */
  public static class HostCreateBuilder extends ToolEppInputBuilder<HostCreateBuilder> {
    private final HostCommand.Create.Builder builder = new HostCommand.Create.Builder();

    private HostCreateBuilder(String hostName) {
      builder.setTargetId(hostName);
    }

    public HostCreateBuilder setInetAddresses(ImmutableSet<InetAddress> inetAddresses) {
      builder.setInetAddresses(inetAddresses);
      return this;
    }

    @Override
    public EppInput build() {
      return EppInput.create(
          EppInput.Create.create(builder.build()), extensions.toArray(new CommandExtension[0]));
    }
  }
}
