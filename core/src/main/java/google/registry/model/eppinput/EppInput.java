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

package google.registry.model.eppinput;

import static google.registry.util.CollectionUtils.nullSafeImmutableCopy;
import static google.registry.util.CollectionUtils.nullToEmptyImmutableCopy;

import com.google.common.base.Ascii;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import google.registry.model.ImmutableObject;
import google.registry.model.contact.ContactCommand;
import google.registry.model.domain.DomainCommand;
import google.registry.model.domain.fee06.FeeCheckCommandExtensionV06;
import google.registry.model.domain.fee06.FeeCreateCommandExtensionV06;
import google.registry.model.domain.fee06.FeeInfoCommandExtensionV06;
import google.registry.model.domain.fee06.FeeRenewCommandExtensionV06;
import google.registry.model.domain.fee06.FeeTransferCommandExtensionV06;
import google.registry.model.domain.fee06.FeeUpdateCommandExtensionV06;
import google.registry.model.domain.fee11.FeeCheckCommandExtensionV11;
import google.registry.model.domain.fee11.FeeCreateCommandExtensionV11;
import google.registry.model.domain.fee11.FeeRenewCommandExtensionV11;
import google.registry.model.domain.fee11.FeeTransferCommandExtensionV11;
import google.registry.model.domain.fee11.FeeUpdateCommandExtensionV11;
import google.registry.model.domain.fee12.FeeCheckCommandExtensionV12;
import google.registry.model.domain.fee12.FeeCreateCommandExtensionV12;
import google.registry.model.domain.fee12.FeeRenewCommandExtensionV12;
import google.registry.model.domain.fee12.FeeTransferCommandExtensionV12;
import google.registry.model.domain.fee12.FeeUpdateCommandExtensionV12;
import google.registry.model.domain.launch.LaunchCheckExtension;
import google.registry.model.domain.launch.LaunchCreateExtension;
import google.registry.model.domain.launch.LaunchDeleteExtension;
import google.registry.model.domain.launch.LaunchInfoExtension;
import google.registry.model.domain.launch.LaunchUpdateExtension;
import google.registry.model.domain.metadata.MetadataExtension;
import google.registry.model.domain.rgp.RgpUpdateExtension;
import google.registry.model.domain.secdns.SecDnsCreateExtension;
import google.registry.model.domain.secdns.SecDnsUpdateExtension;
import google.registry.model.domain.superuser.DomainDeleteSuperuserExtension;
import google.registry.model.domain.superuser.DomainTransferRequestSuperuserExtension;
import google.registry.model.domain.superuser.DomainUpdateSuperuserExtension;
import google.registry.model.domain.token.AllocationTokenExtension;
import google.registry.model.eppinput.ResourceCommand.ResourceCheck;
import google.registry.model.eppinput.ResourceCommand.SingleResourceCommand;
import google.registry.model.host.HostCommand;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import javax.annotation.Nullable;
import javax.xml.bind.annotation.XmlAttribute;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlElementRef;
import javax.xml.bind.annotation.XmlElementRefs;
import javax.xml.bind.annotation.XmlElementWrapper;
import javax.xml.bind.annotation.XmlElements;
import javax.xml.bind.annotation.XmlEnumValue;
import javax.xml.bind.annotation.XmlRootElement;
import javax.xml.bind.annotation.XmlSchema;
import javax.xml.bind.annotation.XmlType;
import javax.xml.bind.annotation.adapters.XmlAdapter;
import javax.xml.bind.annotation.adapters.XmlJavaTypeAdapter;

/** This class represents the root EPP XML element for input. */
@XmlRootElement(name = "epp")
public class EppInput extends ImmutableObject {

  @XmlElements({
      @XmlElement(name = "command", type = CommandWrapper.class),
      @XmlElement(name = "hello", type = Hello.class) })
  CommandWrapper commandWrapper;

  public CommandWrapper getCommandWrapper() {
    return commandWrapper;
  }

  /**
   * Returns the EPP command name, defined as the name of the {@code InnerCommand} element within
   * the {@code <command>} element (e.g. "create" or "poll"), or "hello" for the hello command.
   */
  public String getCommandType() {
    return Ascii.toLowerCase((commandWrapper instanceof Hello)
        ? Hello.class.getSimpleName()
        : commandWrapper.getCommand().getClass().getSimpleName());
  }

  /**
   * Returns the EPP resource type ("domain", "contact", or "host") for commands that operate on
   * EPP resources, otherwise absent.
   */
  public Optional<String> getResourceType() {
    ResourceCommand resourceCommand = getResourceCommand();
    if (resourceCommand != null) {
       XmlSchema xmlSchemaAnnotation =
           resourceCommand.getClass().getPackage().getAnnotation(XmlSchema.class);
       if (xmlSchemaAnnotation != null && xmlSchemaAnnotation.xmlns().length > 0) {
         return Optional.of(xmlSchemaAnnotation.xmlns()[0].prefix());
       }
    }
    return Optional.empty();
  }

  /** Returns whether this EppInput represents a command that operates on domains. */
  public boolean isDomainType() {
    return getResourceType().orElse("").equals("domain");
  }

  @Nullable
  private ResourceCommand getResourceCommand() {
    InnerCommand innerCommand = commandWrapper.getCommand();
    return innerCommand instanceof ResourceCommandWrapper
        ? ((ResourceCommandWrapper) innerCommand).getResourceCommand()
        : null;
  }

  /**
   * Returns the target ID (name for domains and hosts, contact ID for contacts) if this command
   * always acts on a single EPP resource, or absent otherwise (e.g. for "check" or "poll").
   */
  public Optional<String> getSingleTargetId() {
    ResourceCommand resourceCommand = getResourceCommand();
    return resourceCommand instanceof SingleResourceCommand
        ? Optional.of(((SingleResourceCommand) resourceCommand).getTargetId())
        : Optional.empty();
  }

  /**
   * Returns all the target IDs (name for domains and hosts, contact ID for contacts) that this
   * command references if it acts on EPP resources, or the empty list otherwise (e.g. for "poll").
   */
  public ImmutableList<String> getTargetIds() {
    ResourceCommand resourceCommand = getResourceCommand();
    if (resourceCommand instanceof SingleResourceCommand) {
      return ImmutableList.of(((SingleResourceCommand) resourceCommand).getTargetId());
    } else if (resourceCommand instanceof ResourceCheck) {
      return ((ResourceCheck) resourceCommand).getTargetIds();
    } else {
      return ImmutableList.of();
    }
  }

  /** Get the extension based on type, or null. If there are multiple, it chooses the first. */
  public <E extends CommandExtension> Optional<E> getSingleExtension(Class<E> clazz) {
    return getCommandWrapper().getExtensions().stream()
        .filter(clazz::isInstance)
        .map(clazz::cast)
        .findFirst();
  }

  /** A tag that goes inside of an EPP {@literal <command>}. */
  public static class InnerCommand extends ImmutableObject {}

  /** A command that has an extension inside of it. */
  public static class ResourceCommandWrapper extends InnerCommand {
    @XmlElementRefs({
        @XmlElementRef(type = ContactCommand.Check.class),
        @XmlElementRef(type = ContactCommand.Create.class),
        @XmlElementRef(type = ContactCommand.Delete.class),
        @XmlElementRef(type = ContactCommand.Info.class),
        @XmlElementRef(type = ContactCommand.Transfer.class),
        @XmlElementRef(type = ContactCommand.Update.class),
        @XmlElementRef(type = DomainCommand.Check.class),
        @XmlElementRef(type = DomainCommand.Create.class),
        @XmlElementRef(type = DomainCommand.Delete.class),
        @XmlElementRef(type = DomainCommand.Info.class),
        @XmlElementRef(type = DomainCommand.Renew.class),
        @XmlElementRef(type = DomainCommand.Transfer.class),
        @XmlElementRef(type = DomainCommand.Update.class),
        @XmlElementRef(type = HostCommand.Check.class),
        @XmlElementRef(type = HostCommand.Create.class),
        @XmlElementRef(type = HostCommand.Delete.class),
        @XmlElementRef(type = HostCommand.Info.class),
        @XmlElementRef(type = HostCommand.Update.class)})
    ResourceCommand resourceCommand;

    public ResourceCommand getResourceCommand() {
      return resourceCommand;
    }
  }

  /** Epp envelope wrapper for check on some objects. */
  public static class Check extends ResourceCommandWrapper {}

  /** Epp envelope wrapper for create of some object. */
  public static class Create extends ResourceCommandWrapper {}

  /** Epp envelope wrapper for delete of some object. */
  public static class Delete extends ResourceCommandWrapper {}

  /** Epp envelope wrapper for info on some object. */
  public static class Info extends ResourceCommandWrapper {}

  /** Epp envelope wrapper for renewing some object. */
  public static class Renew extends ResourceCommandWrapper {}

  /** Epp envelope wrapper for transferring some object. */
  public static class Transfer extends ResourceCommandWrapper {

    /** Enum of the possible values for the "op" attribute in transfer flows. */
    public enum TransferOp {
      @XmlEnumValue("approve")
      APPROVE,

      @XmlEnumValue("cancel")
      CANCEL,

      @XmlEnumValue("query")
      QUERY,

      @XmlEnumValue("reject")
      REJECT,

      @XmlEnumValue("request")
      REQUEST
    }

    @XmlAttribute(name = "op")
    TransferOp transferOp;

    public TransferOp getTransferOp() {
      return transferOp;
    }
  }

  /** Epp envelope wrapper for update of some object. */
  public static class Update extends ResourceCommandWrapper {}

  /** Poll command. */
  public static class Poll extends InnerCommand {

    /** Enum of the possible values for the "op" attribute in poll commands. */
    public enum PollOp {

      /** Acknowledge a poll message was received. */
      @XmlEnumValue("ack")
      ACK,

      /** Request the next poll message. */
      @XmlEnumValue("req")
      REQUEST
    }

    @XmlAttribute
    PollOp op;

    @XmlAttribute
    String msgID;

    public PollOp getPollOp() {
      return op;
    }

    public String getMessageId() {
      return msgID;
    }
  }

  /** Login command. */
  public static class Login extends InnerCommand {
    @XmlElement(name = "clID")
    String clientId;

    @XmlElement(name = "pw")
    String password;

    @XmlElement(name = "newPW")
    String newPassword;

    Options options;

    @XmlElement(name = "svcs")
    Services services;

    public String getClientId() {
      return clientId;
    }

    public String getPassword() {
      return password;
    }

    public String getNewPassword() {
      return newPassword;
    }

    public Options getOptions() {
      return options;
    }

    public Services getServices() {
      return services;
    }
  }

  /** Logout command. */
  public static class Logout extends InnerCommand {}

  /** The "command" element that holds an actual command inside of it. */
  @XmlType(propOrder = {"command", "extension", "clTRID"})
  public static class CommandWrapper extends ImmutableObject {
    @XmlElements({
      @XmlElement(name = "check", type = Check.class),
      @XmlElement(name = "create", type = Create.class),
      @XmlElement(name = "delete", type = Delete.class),
      @XmlElement(name = "info", type = Info.class),
      @XmlElement(name = "login", type = Login.class),
      @XmlElement(name = "logout", type = Logout.class),
      @XmlElement(name = "poll", type = Poll.class),
      @XmlElement(name = "renew", type = Renew.class),
      @XmlElement(name = "transfer", type = Transfer.class),
      @XmlElement(name = "update", type = Update.class)
    })
    InnerCommand command;

    /** Zero or more command extensions. */
    @XmlElementRefs({
      // Fee extension version 0.6
      @XmlElementRef(type = FeeCheckCommandExtensionV06.class),
      @XmlElementRef(type = FeeInfoCommandExtensionV06.class),
      @XmlElementRef(type = FeeCreateCommandExtensionV06.class),
      @XmlElementRef(type = FeeRenewCommandExtensionV06.class),
      @XmlElementRef(type = FeeTransferCommandExtensionV06.class),
      @XmlElementRef(type = FeeUpdateCommandExtensionV06.class),

      // Fee extension version 0.11
      @XmlElementRef(type = FeeCheckCommandExtensionV11.class),
      @XmlElementRef(type = FeeCreateCommandExtensionV11.class),
      @XmlElementRef(type = FeeRenewCommandExtensionV11.class),
      @XmlElementRef(type = FeeTransferCommandExtensionV11.class),
      @XmlElementRef(type = FeeUpdateCommandExtensionV11.class),

      // Fee extension version 0.12
      @XmlElementRef(type = FeeCheckCommandExtensionV12.class),
      @XmlElementRef(type = FeeCreateCommandExtensionV12.class),
      @XmlElementRef(type = FeeRenewCommandExtensionV12.class),
      @XmlElementRef(type = FeeTransferCommandExtensionV12.class),
      @XmlElementRef(type = FeeUpdateCommandExtensionV12.class),

      // Launch phase extensions
      @XmlElementRef(type = LaunchCheckExtension.class),
      @XmlElementRef(type = LaunchCreateExtension.class),
      @XmlElementRef(type = LaunchDeleteExtension.class),
      @XmlElementRef(type = LaunchInfoExtension.class),
      @XmlElementRef(type = LaunchUpdateExtension.class),

      // Superuser extensions
      @XmlElementRef(type = DomainDeleteSuperuserExtension.class),
      @XmlElementRef(type = DomainTransferRequestSuperuserExtension.class),
      @XmlElementRef(type = DomainUpdateSuperuserExtension.class),

      // Other extensions
      @XmlElementRef(type = AllocationTokenExtension.class),
      @XmlElementRef(type = MetadataExtension.class),
      @XmlElementRef(type = RgpUpdateExtension.class),
      @XmlElementRef(type = SecDnsCreateExtension.class),
      @XmlElementRef(type = SecDnsUpdateExtension.class)
    })
    @XmlElementWrapper
    List<CommandExtension> extension;

    @Nullable String clTRID;

    /**
     * Returns the client transaction ID.
     *
     * <p>This is optional (i.e. it may not be specified) per RFC 5730.
     */
    public Optional<String> getClTrid() {
      return Optional.ofNullable(clTRID);
    }

    public InnerCommand getCommand() {
      return command;
    }

    public ImmutableList<CommandExtension> getExtensions() {
      return nullToEmptyImmutableCopy(extension);
    }
  }

  /** Empty type to represent the empty "hello" command. */
  public static class Hello extends CommandWrapper {}

  /** An options object inside of {@link Login}. */
  public static class Options extends ImmutableObject {
    @XmlJavaTypeAdapter(VersionAdapter.class)
    String version;

    @XmlElement(name = "lang")
    String language;

    public String getLanguage() {
      return language;
    }
  }

  /** A services object inside of {@link Login}. */
  public static class Services extends ImmutableObject {
    @XmlElement(name = "objURI")
    Set<String> objectServices;

    @XmlElementWrapper(name = "svcExtension")
    @XmlElement(name = "extURI")
    Set<String> serviceExtensions;

    public ImmutableSet<String> getObjectServices() {
      return nullSafeImmutableCopy(objectServices);
    }

    public ImmutableSet<String> getServiceExtensions() {
      return nullSafeImmutableCopy(serviceExtensions);
    }
  }

  /**
   * RFC 5730 says we should check the version and return special error code 2100 if it isn't
   * what we support, but it also specifies a schema that only allows 1.0 in the version field, so
   * any other version doesn't validate. As a result, if we didn't do this here it would throw a
   * {@code SyntaxErrorException} when it failed to validate.
   *
   * @see <a href="http://tools.ietf.org/html/rfc5730#page-41">
   *     RFC 5730 - EPP - Command error responses</a>
   */
  public static class VersionAdapter extends XmlAdapter<String, String>  {
    @Override
    public String unmarshal(String version) throws Exception {
      if (!"1.0".equals(version)) {
        throw new WrongProtocolVersionException();
      }
      return version;
    }

    @Override
    public String marshal(String ignored) {
      throw new UnsupportedOperationException();
    }
  }

  /** Marker interface for types that can go in the {@link CommandWrapper#extension} field. */
  public interface CommandExtension {}

  /** Exception to throw if encountering a protocol version other than "1.0". */
  public static class WrongProtocolVersionException extends Exception {}
}
