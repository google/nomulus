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

import static com.google.common.base.Preconditions.checkState;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.xml.ValidationMode.LENIENT;
import static google.registry.xml.ValidationMode.STRICT;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.base.Throwables;
import com.google.common.flogger.FluentLogger;
import com.googlecode.objectify.Key;
import google.registry.flows.EppException.CommandUseErrorException;
import google.registry.flows.EppException.ParameterValueRangeErrorException;
import google.registry.flows.EppException.SyntaxErrorException;
import google.registry.flows.EppException.UnimplementedProtocolVersionException;
import google.registry.flows.custom.EntityChanges;
import google.registry.model.EppResource;
import google.registry.model.eppcommon.EppXmlTransformer;
import google.registry.model.eppinput.EppInput.WrongProtocolVersionException;
import google.registry.model.eppoutput.EppOutput;
import google.registry.model.host.InetAddressAdapter.IpVersionMismatchException;
import google.registry.model.ofy.ObjectifyService;
import google.registry.model.reporting.HistoryEntry;
import google.registry.model.translators.CurrencyUnitAdapter.UnknownCurrencyException;
import google.registry.xml.XmlException;
import java.util.List;

/** Static utility functions for flows. */
public final class FlowUtils {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private FlowUtils() {}

  /** Validate that there is a logged in client. */
  public static void validateClientIsLoggedIn(String clientId) throws EppException {
    if (clientId.isEmpty()) {
      throw new NotLoggedInException();
    }
  }

  /** Persists the saves and deletes in an {@link EntityChanges} to Datastore. */
  public static void persistEntityChanges(EntityChanges entityChanges) {
    tm().putAll(entityChanges.getSaves());
    tm().delete(entityChanges.getDeletes());
  }

  /**
   * Unmarshal bytes into Epp classes. Does the same as {@link EppXmlTransformer#unmarshal(Class,
   * byte[])} but with exception-handling logic to throw {@link EppException} instead.
   */
  public static <T> T unmarshalEpp(Class<T> clazz, byte[] bytes) throws EppException {
    try {
      return EppXmlTransformer.unmarshal(clazz, bytes);
    } catch (XmlException e) {
      // If this XmlException is wrapping a known type find it. If not, it's a syntax error.
      List<Throwable> causalChain = Throwables.getCausalChain(e);
      if (causalChain.stream().anyMatch(IpVersionMismatchException.class::isInstance)) {
        throw new IpAddressVersionMismatchException();
      }
      if (causalChain.stream().anyMatch(WrongProtocolVersionException.class::isInstance)) {
        throw new UnimplementedProtocolVersionException();
      }
      if (causalChain.stream().anyMatch(UnknownCurrencyException.class::isInstance)) {
        throw new UnknownCurrencyEppException();
      }
      throw new GenericXmlSyntaxErrorException(e.getMessage());
    }
  }

  public static byte[] marshalWithLenientRetry(EppOutput eppOutput) {
    checkState(eppOutput != null);
    // We need to marshal to a string instead of writing the response directly to the servlet's
    // response writer, so that partial results don't get written on failure.
    try {
      return EppXmlTransformer.marshal(eppOutput, STRICT);
    } catch (XmlException e) {
      // We failed to marshal with validation. This is very bad, but we can potentially still send
      // back slightly invalid xml, so try again without validation.
      try {
        byte[] lenient = EppXmlTransformer.marshal(eppOutput, LENIENT);
        // Marshaling worked even though the results didn't validate against the schema.
        logger.atSevere().withCause(e).log(
            "Result marshaled but did not validate: %s", new String(lenient, UTF_8));
        return lenient;
      } catch (XmlException e2) {
        throw new RuntimeException(e2); // Failing to marshal at all is not recoverable.
      }
    }
  }

  public static <H extends HistoryEntry> Key<H> createHistoryKey(
      EppResource parent, Class<H> clazz) {
    return Key.create(Key.create(parent), clazz, ObjectifyService.allocateId());
  }

  /** Registrar is not logged in. */
  public static class NotLoggedInException extends CommandUseErrorException {
    public NotLoggedInException() {
      super("Registrar is not logged in.");
    }
  }

  /** IP address version mismatch. */
  public static class IpAddressVersionMismatchException extends ParameterValueRangeErrorException {
    public IpAddressVersionMismatchException() {
      super("IP adddress version mismatch");
    }
  }

  /** Unknown currency. */
  public static class UnknownCurrencyEppException extends ParameterValueRangeErrorException {
    public UnknownCurrencyEppException() {
      super("Unknown currency.");
    }
  }

  /** Generic XML syntax error that can be thrown by any flow. */
  public static class GenericXmlSyntaxErrorException extends SyntaxErrorException {
    public GenericXmlSyntaxErrorException(String message) {
      super(message);
    }
  }
}
