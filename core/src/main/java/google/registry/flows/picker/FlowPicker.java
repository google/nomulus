// Copyright 2025 The Nomulus Authors. All Rights Reserved.
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

package google.registry.flows.picker;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableTable;
import com.google.common.collect.Table;
import google.registry.flows.EppException;
import google.registry.flows.EppException.SyntaxErrorException;
import google.registry.flows.EppException.UnimplementedCommandException;
import google.registry.flows.Flow;
import google.registry.flows.contact.ContactCheckFlow;
import google.registry.flows.contact.ContactCreateFlow;
import google.registry.flows.contact.ContactDeleteFlow;
import google.registry.flows.contact.ContactInfoFlow;
import google.registry.flows.contact.ContactTransferApproveFlow;
import google.registry.flows.contact.ContactTransferCancelFlow;
import google.registry.flows.contact.ContactTransferQueryFlow;
import google.registry.flows.contact.ContactTransferRejectFlow;
import google.registry.flows.contact.ContactTransferRequestFlow;
import google.registry.flows.contact.ContactUpdateFlow;
import google.registry.flows.domain.DomainCheckFlow;
import google.registry.flows.domain.DomainClaimsCheckFlow;
import google.registry.flows.domain.DomainCreateFlow;
import google.registry.flows.domain.DomainDeleteFlow;
import google.registry.flows.domain.DomainInfoFlow;
import google.registry.flows.domain.DomainRenewFlow;
import google.registry.flows.domain.DomainRestoreRequestFlow;
import google.registry.flows.domain.DomainTransferApproveFlow;
import google.registry.flows.domain.DomainTransferCancelFlow;
import google.registry.flows.domain.DomainTransferQueryFlow;
import google.registry.flows.domain.DomainTransferRejectFlow;
import google.registry.flows.domain.DomainTransferRequestFlow;
import google.registry.flows.domain.DomainUpdateFlow;
import google.registry.flows.host.HostCheckFlow;
import google.registry.flows.host.HostCreateFlow;
import google.registry.flows.host.HostDeleteFlow;
import google.registry.flows.host.HostInfoFlow;
import google.registry.flows.host.HostUpdateFlow;
import google.registry.flows.poll.PollAckFlow;
import google.registry.flows.poll.PollRequestFlow;
import google.registry.flows.session.HelloFlow;
import google.registry.flows.session.LoginFlow;
import google.registry.flows.session.LogoutFlow;
import google.registry.model.contact.ContactCommand;
import google.registry.model.domain.DomainCommand;
import google.registry.model.domain.launch.LaunchCheckExtension;
import google.registry.model.domain.launch.LaunchCheckExtension.CheckType;
import google.registry.model.domain.launch.LaunchPhase;
import google.registry.model.domain.rgp.RestoreCommand.RestoreOp;
import google.registry.model.domain.rgp.RgpUpdateExtension;
import google.registry.model.eppinput.EppInput;
import google.registry.model.eppinput.EppInput.Hello;
import google.registry.model.eppinput.EppInput.InnerCommand;
import google.registry.model.eppinput.EppInput.Login;
import google.registry.model.eppinput.EppInput.Logout;
import google.registry.model.eppinput.EppInput.Poll;
import google.registry.model.eppinput.EppInput.ResourceCommandWrapper;
import google.registry.model.eppinput.EppInput.Transfer;
import google.registry.model.eppinput.EppInput.Transfer.TransferOp;
import google.registry.model.eppinput.ResourceCommand;
import google.registry.model.host.HostCommand;
import java.util.Map;
import java.util.Optional;

/** Class that picks a flow to handle a given EPP command. */
public class FlowPicker {

  /** Marker class for unimplemented flows. */
  private abstract static class UnimplementedFlow implements Flow {}

  /** Marker class for unimplemented restore flows. */
  private abstract static class UnimplementedRestoreFlow implements Flow {}

  /** A function type that takes an {@link EppInput} and returns a {@link Flow} class. */
  private abstract static class FlowProvider {
    /** Get the flow associated with this {@link EppInput} or return null to signal no match. */
    Class<? extends Flow> get(EppInput eppInput) {
      InnerCommand innerCommand = eppInput.getCommandWrapper().getCommand();
      return get(eppInput, innerCommand, (innerCommand instanceof ResourceCommandWrapper)
          ? ((ResourceCommandWrapper) innerCommand).getResourceCommand() : null);
    }

    /**
     * Subclasses need to implement this to examine the parameters and choose a flow (or null if
     * the subclass doesn't know of an appropriate flow.
     */
    abstract Class<? extends Flow> get(
        EppInput eppInput, InnerCommand innerCommand, ResourceCommand resourceCommand);
  }

  /** The hello flow is keyed on a special {@code CommandWrapper} type. */
  private static final FlowProvider HELLO_FLOW_PROVIDER = new FlowProvider() {
    @Override
    Class<? extends Flow> get(
        EppInput eppInput, InnerCommand innerCommand, ResourceCommand resourceCommand) {
      return eppInput.getCommandWrapper() instanceof Hello ? HelloFlow.class : null;
    }};

  /** Session flows like login and logout are keyed only on the {@link InnerCommand} type. */
  private static final FlowProvider SESSION_FLOW_PROVIDER = new FlowProvider() {
    private final Map<Class<?>, Class<? extends Flow>> commandFlows =
      ImmutableMap.of(
          Login.class, LoginFlow.class,
          Logout.class, LogoutFlow.class);

    @Override
    Class<? extends Flow> get(
        EppInput eppInput, InnerCommand innerCommand, ResourceCommand resourceCommand) {
      return innerCommand == null ? null : commandFlows.get(innerCommand.getClass());
    }};

  /** Poll flows have an {@link InnerCommand} of type {@link Poll}. */
  private static final FlowProvider POLL_FLOW_PROVIDER =
      new FlowProvider() {
        @Override
        Class<? extends Flow> get(
            EppInput eppInput, InnerCommand innerCommand, ResourceCommand resourceCommand) {
          if (!(innerCommand instanceof Poll)) {
            return null;
          }
          return switch (((Poll) innerCommand).getPollOp()) {
            case ACK -> PollAckFlow.class;
            case REQUEST -> PollRequestFlow.class;
            default -> UnimplementedFlow.class;
          };
        }
      };

  /**
   * The domain restore command is technically a domain {@literal <update>}, but logically a totally
   * separate flow.
   *
   * <p>This provider must be tried before {@link #RESOURCE_CRUD_FLOW_PROVIDER}. Otherwise, the
   * regular domain update flow will match first.
   */
  private static final FlowProvider DOMAIN_RESTORE_FLOW_PROVIDER =
      new FlowProvider() {
        @Override
        Class<? extends Flow> get(
            EppInput eppInput, InnerCommand innerCommand, ResourceCommand resourceCommand) {
          if (!(resourceCommand instanceof DomainCommand.Update)) {
            return null;
          }
          Optional<RgpUpdateExtension> rgpUpdateExtension =
              eppInput.getSingleExtension(RgpUpdateExtension.class);
          if (rgpUpdateExtension.isEmpty()) {
            return null;
          }
          // Restore command with an op of "report" is not currently supported.
          return (rgpUpdateExtension.get().getRestoreCommand().getRestoreOp() == RestoreOp.REQUEST)
              ? DomainRestoreRequestFlow.class
              : UnimplementedRestoreFlow.class;
        }
      };

  /**
   * The claims check flow is keyed on the type of the {@link ResourceCommand} and on having the
   * correct extension with a specific phase value.
   */
  private static final FlowProvider DOMAIN_CHECK_FLOW_PROVIDER =
      new FlowProvider() {
        @Override
        Class<? extends Flow> get(
            EppInput eppInput, InnerCommand innerCommand, ResourceCommand resourceCommand) {
          if (!(resourceCommand instanceof DomainCommand.Check)) {
            return null;
          }
          Optional<LaunchCheckExtension> launchCheck =
              eppInput.getSingleExtension(LaunchCheckExtension.class);
          if (launchCheck.isEmpty()
              || CheckType.AVAILABILITY.equals(launchCheck.get().getCheckType())) {
            // We don't distinguish between registry phases for "avail", so don't bother checking
            // phase.
            return DomainCheckFlow.class;
          }
          if (CheckType.CLAIMS.equals(launchCheck.get().getCheckType())
              && LaunchPhase.CLAIMS.equals(launchCheck.get().getPhase())) {
            return DomainClaimsCheckFlow.class;
          }
          return null;
        }
      };

  /** General resource CRUD flows are keyed on the type of their {@link ResourceCommand}. */
  private static final FlowProvider RESOURCE_CRUD_FLOW_PROVIDER = new FlowProvider() {
    private final Map<Class<?>, Class<? extends Flow>> resourceCrudFlows =
        new ImmutableMap.Builder<Class<?>, Class<? extends Flow>>()
            .put(ContactCommand.Check.class, ContactCheckFlow.class)
            .put(ContactCommand.Create.class, ContactCreateFlow.class)
            .put(ContactCommand.Delete.class, ContactDeleteFlow.class)
            .put(ContactCommand.Info.class, ContactInfoFlow.class)
            .put(ContactCommand.Update.class, ContactUpdateFlow.class)
            .put(DomainCommand.Create.class, DomainCreateFlow.class)
            .put(DomainCommand.Delete.class, DomainDeleteFlow.class)
            .put(DomainCommand.Info.class, DomainInfoFlow.class)
            .put(DomainCommand.Renew.class, DomainRenewFlow.class)
            .put(DomainCommand.Update.class, DomainUpdateFlow.class)
            .put(HostCommand.Check.class, HostCheckFlow.class)
            .put(HostCommand.Create.class, HostCreateFlow.class)
            .put(HostCommand.Delete.class, HostDeleteFlow.class)
            .put(HostCommand.Info.class, HostInfoFlow.class)
            .put(HostCommand.Update.class, HostUpdateFlow.class)
            .build();

    @Override
    Class<? extends Flow> get(
        EppInput eppInput, InnerCommand innerCommand, ResourceCommand resourceCommand) {
      return resourceCommand == null ? null : resourceCrudFlows.get(resourceCommand.getClass());
    }};

  /** Transfer flows have an {@link InnerCommand} of type {@link Transfer}. */
  private static final FlowProvider TRANSFER_FLOW_PROVIDER = new FlowProvider() {
    private final Table<Class<?>, TransferOp, Class<? extends Flow>> transferFlows = ImmutableTable
        .<Class<?>, TransferOp, Class<? extends Flow>>builder()
        .put(ContactCommand.Transfer.class, TransferOp.APPROVE, ContactTransferApproveFlow.class)
        .put(ContactCommand.Transfer.class, TransferOp.CANCEL, ContactTransferCancelFlow.class)
        .put(ContactCommand.Transfer.class, TransferOp.QUERY, ContactTransferQueryFlow.class)
        .put(ContactCommand.Transfer.class, TransferOp.REJECT, ContactTransferRejectFlow.class)
        .put(ContactCommand.Transfer.class, TransferOp.REQUEST, ContactTransferRequestFlow.class)
        .put(DomainCommand.Transfer.class, TransferOp.APPROVE, DomainTransferApproveFlow.class)
        .put(DomainCommand.Transfer.class, TransferOp.CANCEL, DomainTransferCancelFlow.class)
        .put(DomainCommand.Transfer.class, TransferOp.QUERY, DomainTransferQueryFlow.class)
        .put(DomainCommand.Transfer.class, TransferOp.REJECT, DomainTransferRejectFlow.class)
        .put(DomainCommand.Transfer.class, TransferOp.REQUEST, DomainTransferRequestFlow.class)
        .build();

    @Override
    Class<? extends Flow> get(
        EppInput eppInput, InnerCommand innerCommand, ResourceCommand resourceCommand) {
      return resourceCommand != null && innerCommand instanceof Transfer
          ? transferFlows.get(resourceCommand.getClass(), ((Transfer) innerCommand).getTransferOp())
          : null;
    }};

  private static final ImmutableList<FlowProvider> FLOW_PROVIDERS =
      ImmutableList.of(
          HELLO_FLOW_PROVIDER,
          SESSION_FLOW_PROVIDER,
          POLL_FLOW_PROVIDER,
          DOMAIN_RESTORE_FLOW_PROVIDER,
          DOMAIN_CHECK_FLOW_PROVIDER,
          RESOURCE_CRUD_FLOW_PROVIDER,
          TRANSFER_FLOW_PROVIDER);

  /** Return the appropriate flow to handle this EPP command. */
  public static Class<? extends Flow> getFlowClass(EppInput eppInput) throws EppException {
    // Do some sanity checking on the input; anything but Hello must have a command type.
    InnerCommand innerCommand = eppInput.getCommandWrapper().getCommand();
    if (innerCommand == null && !(eppInput.getCommandWrapper() instanceof Hello)) {
      throw new MissingCommandException();
    }
    // Try the FlowProviders until we find a match. The order matters because it's possible to
    // match multiple FlowProviders and so more specific matches are tried first.
    for (FlowProvider flowProvider : FLOW_PROVIDERS) {
      Class<? extends Flow> flowClass = flowProvider.get(eppInput);
      if (flowClass == UnimplementedFlow.class) {
        break;  // We found it, but it's marked as not implemented.
      } else if (flowClass == UnimplementedRestoreFlow.class) {
        throw new UnimplementedCommandException(
            "Domain restores are approved and enacted instantly, "
                + "therefore domain restore reports are not supported");
      } else if (flowClass != null) {
        return flowClass;  // We found it!
      }
    }
    // Nothing usable was found, so throw an exception.
    throw new UnimplementedCommandException(innerCommand);
  }

  /** Command missing. */
  static class MissingCommandException extends SyntaxErrorException {
    public MissingCommandException() {
      super("Command missing");
    }
  }
}
