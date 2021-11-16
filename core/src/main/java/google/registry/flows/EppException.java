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

package google.registry.flows;

import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import google.registry.model.annotations.ExternalMessagingName;
import google.registry.model.eppinput.EppInput.InnerCommand;
import google.registry.model.eppinput.EppInput.ResourceCommandWrapper;
import google.registry.model.eppoutput.Result;
import google.registry.model.eppoutput.Result.Code;
import google.registry.persistence.transaction.TransactionManagerFactory.ReadOnlyModeException;
import java.lang.annotation.Documented;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import javax.annotation.Nullable;

/** Exception used to propagate all failures containing one or more EPP responses. */
public abstract class EppException extends Exception {

  private final Result result;

  /** Create an EppException with a custom message. */
  private EppException(String message) {
    this(message, null);
  }

  /** Create an EppException with a custom message and cause. */
  private EppException(String message, @Nullable Throwable cause) {
    super(message, cause);
    Code code = getClass().getAnnotation(EppResultCode.class).value();
    Preconditions.checkState(!code.isSuccess());
    this.result = Result.create(code, message);
  }

  /** Create an EppException with the default message for this code. */
  private EppException() {
    this(null);
  }

  public Result getResult() {
    return result;
  }

  /** Annotation for associating an EPP Result.Code value with an EppException subclass. */
  @Documented
  @Inherited
  @Retention(RUNTIME)
  @Target(TYPE)
  public @interface EppResultCode {
    /** The Code value associated with this exception. */
    Code value();
  }

  /** Abstract exception class. Do not throw this directly or catch in tests. */
  @EppResultCode(Code.AUTHENTICATION_ERROR)
  public abstract static class AuthenticationErrorException extends EppException {
    public AuthenticationErrorException(String message) {
      super(message);
    }
  }

  /** Abstract exception class. Do not throw this directly or catch in tests. */
  @EppResultCode(Code.AUTHENTICATION_ERROR_CLOSING_CONNECTION)
  public abstract static class AuthenticationErrorClosingConnectionException extends EppException {
    public AuthenticationErrorClosingConnectionException(String message) {
      super(message);
    }
  }

  /** Abstract exception class. Do not throw this directly or catch in tests. */
  @EppResultCode(Code.AUTHORIZATION_ERROR)
  public abstract static class AuthorizationErrorException extends EppException {
    public AuthorizationErrorException(String message) {
      super(message);
    }
  }

  /** Abstract exception class. Do not throw this directly or catch in tests. */
  @EppResultCode(Code.INVALID_AUTHORIZATION_INFORMATION_ERROR)
  public abstract static class InvalidAuthorizationInformationErrorException extends EppException {
    public InvalidAuthorizationInformationErrorException(String message) {
      super(message);
    }
  }

  /** Abstract exception class. Do not throw this directly or catch in tests. */
  @EppResultCode(Code.COMMAND_USE_ERROR)
  public abstract static class CommandUseErrorException extends EppException {
    public CommandUseErrorException(String message) {
      super(message);
    }
  }

  /** Abstract exception class. Do not throw this directly or catch in tests. */
  @EppResultCode(Code.OBJECT_EXISTS)
  public abstract static class ObjectAlreadyExistsException extends EppException {
    public ObjectAlreadyExistsException(String message) {
      super(message);
    }
  }

  /** Abstract exception class. Do not throw this directly or catch in tests. */
  @EppResultCode(Code.OBJECT_DOES_NOT_EXIST)
  public abstract static class ObjectDoesNotExistException extends EppException {
    public ObjectDoesNotExistException(Class<?> type, String id) {
      super(
          String.format(
              "The %s with given ID (%s) doesn't exist.",
              type.isAnnotationPresent(ExternalMessagingName.class)
                  ? type.getAnnotation(ExternalMessagingName.class).value()
                  : "object",
              id));
    }

    public ObjectDoesNotExistException(Class<?> type, ImmutableSet<String> ids) {
      super(
          String.format(
              "The %s with given IDs (%s) don't exist.",
              type.isAnnotationPresent(ExternalMessagingName.class)
                  ? type.getAnnotation(ExternalMessagingName.class).value() + " objects"
                  : "objects",
              Joiner.on(',').join(ids)));
    }
  }

  /** Abstract exception class. Do not throw this directly or catch in tests. */
  @EppResultCode(Code.OBJECT_PENDING_TRANSFER)
  public abstract static class ObjectPendingTransferException extends EppException {
    public ObjectPendingTransferException(String id) {
      super(String.format("Object with given ID (%s) already has a pending transfer.", id));
    }
  }

  /** Abstract exception class. Do not throw this directly or catch in tests. */
  @EppResultCode(Code.OBJECT_NOT_PENDING_TRANSFER)
  public abstract static class ObjectNotPendingTransferException extends EppException {
    public ObjectNotPendingTransferException(String id) {
      super(String.format("Object with given ID (%s) does not have a pending transfer.", id));
    }
  }

  /** Abstract exception class. Do not throw this directly or catch in tests. */
  @EppResultCode(Code.ASSOCIATION_PROHIBITS_OPERATION)
  public abstract static class AssociationProhibitsOperationException extends EppException {
    public AssociationProhibitsOperationException(String message) {
      super(message);
    }
  }

  /** Abstract exception class. Do not throw this directly or catch in tests. */
  @EppResultCode(Code.PARAMETER_VALUE_POLICY_ERROR)
  public abstract static class ParameterValuePolicyErrorException extends EppException {
    public ParameterValuePolicyErrorException(String message) {
      super(message);
    }
  }

  /** Abstract exception class. Do not throw this directly or catch in tests. */
  @EppResultCode(Code.PARAMETER_VALUE_RANGE_ERROR)
  public abstract static class ParameterValueRangeErrorException extends EppException {
    public ParameterValueRangeErrorException(String message) {
      super(message);
    }
  }

  /** Abstract exception class. Do not throw this directly or catch in tests. */
  @EppResultCode(Code.PARAMETER_VALUE_SYNTAX_ERROR)
  public abstract static class ParameterValueSyntaxErrorException extends EppException {
    public ParameterValueSyntaxErrorException(String message) {
      super(message);
    }
  }

  /** Abstract exception class. Do not throw this directly or catch in tests. */
  @EppResultCode(Code.REQUIRED_PARAMETER_MISSING)
  public abstract static class RequiredParameterMissingException extends EppException {
    public RequiredParameterMissingException(String message) {
      super(message);
    }
  }

  /** Abstract exception class. Do not throw this directly or catch in tests. */
  @EppResultCode(Code.STATUS_PROHIBITS_OPERATION)
  public abstract static class StatusProhibitsOperationException extends EppException {
    public StatusProhibitsOperationException(String message) {
      super(message);
    }
  }

  /** Abstract exception class. Do not throw this directly or catch in tests. */
  @EppResultCode(Code.SYNTAX_ERROR)
  public abstract static class SyntaxErrorException extends EppException {
    public SyntaxErrorException(String message) {
      super(message);
    }
  }

  /** Specified command is not implemented. */
  @EppResultCode(Code.UNIMPLEMENTED_COMMAND)
  public static class UnimplementedCommandException extends EppException {

    public UnimplementedCommandException(InnerCommand command) {
      super(String.format(
          "No flow found for %s with extension %s",
          command.getClass().getSimpleName(),
          command instanceof ResourceCommandWrapper
              ? ((ResourceCommandWrapper) command).getResourceCommand().getClass().getSimpleName()
              : null));
    }

    public UnimplementedCommandException(String message) {
      super(message);
    }
  }

  /** Abstract exception class. Do not throw this directly or catch in tests. */
  @EppResultCode(Code.UNIMPLEMENTED_OPTION)
  public abstract static class UnimplementedOptionException extends EppException {
    public UnimplementedOptionException(String message) {
      super(message);
    }
  }

  /** Specified extension is not implemented. */
  @EppResultCode(Code.UNIMPLEMENTED_EXTENSION)
  public static class UnimplementedExtensionException extends EppException {
    public UnimplementedExtensionException() {
      super("Specified extension is not implemented");
    }
  }

  /** Specified object service is not implemented. */
  @EppResultCode(Code.UNIMPLEMENTED_OBJECT_SERVICE)
  public static class UnimplementedObjectServiceException extends EppException {
    public UnimplementedObjectServiceException() {
      super("Specified object service is not implemented");
    }
  }

  /** Specified protocol version is not implemented. */
  @EppResultCode(Code.UNIMPLEMENTED_PROTOCOL_VERSION)
  public static class UnimplementedProtocolVersionException extends EppException {
    public UnimplementedProtocolVersionException() {
      super("Specified protocol version is not implemented");
    }
  }

  /** Registry is currently undergoing maintenance and is in read-only mode. */
  @EppResultCode(Code.COMMAND_FAILED)
  public static class ReadOnlyModeEppException extends EppException {
    ReadOnlyModeEppException(ReadOnlyModeException cause) {
      super("Registry is currently undergoing maintenance and is in read-only mode", cause);
    }
  }
}
