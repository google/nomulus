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

import static com.google.common.collect.Sets.difference;
import static com.google.common.collect.Sets.intersection;
import static google.registry.model.domain.fee.Fee.FEE_EXTENSION_URIS;
import static google.registry.model.eppcommon.ProtocolDefinition.ServiceExtension.getCommandExtensionUri;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.flogger.FluentLogger;
import google.registry.flows.EppException.CommandUseErrorException;
import google.registry.flows.EppException.SyntaxErrorException;
import google.registry.flows.EppException.UnimplementedExtensionException;
import google.registry.flows.FlowModule.RegistrarId;
import google.registry.flows.FlowModule.Superuser;
import google.registry.flows.exceptions.OnlyToolCanPassMetadataException;
import google.registry.flows.exceptions.UnauthorizedForSuperuserExtensionException;
import google.registry.model.domain.metadata.MetadataExtension;
import google.registry.model.domain.superuser.SuperuserExtension;
import google.registry.model.eppinput.EppInput;
import google.registry.model.eppinput.EppInput.CommandExtension;
import java.util.Set;
import javax.inject.Inject;

/**
 * Helper to validate extensions on an EPP command.
 *
 * <p>This class checks that the declared extension URIs in an EPP request as well as the actually
 * supplied extensions in the XML are compatible with the extensions supported by a flow.
 */
public final class ExtensionManager {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  /** Denylist of extension URIs that cause an error if they are used without being declared. */
  private static final ImmutableSet<String> UNDECLARED_URIS_DENYLIST = FEE_EXTENSION_URIS;

  private final ImmutableSet.Builder<Class<? extends CommandExtension>> implementedBuilder =
      new ImmutableSet.Builder<>();

  @Inject EppInput eppInput;
  @Inject SessionMetadata sessionMetadata;
  @Inject @RegistrarId String registrarId;
  @Inject @Superuser boolean isSuperuser;
  @Inject Class<? extends Flow> flowClass;
  @Inject EppRequestSource eppRequestSource;
  @Inject ExtensionManager() {}

  @SafeVarargs
  public final void register(Class<? extends CommandExtension>... extension) {
    implementedBuilder.add(extension);
  }

  public void validate() throws EppException {
    ImmutableSet.Builder<Class<? extends CommandExtension>> suppliedBuilder =
        new ImmutableSet.Builder<>();
    for (CommandExtension extension : eppInput.getCommandWrapper().getExtensions()) {
      suppliedBuilder.add(extension.getClass());
    }
    ImmutableSet<Class<? extends CommandExtension>> suppliedExtensions = suppliedBuilder.build();
    ImmutableSet<Class<? extends CommandExtension>> implementedExtensions =
        implementedBuilder.build();
    ImmutableList<CommandExtension> suppliedExtensionInstances =
        eppInput.getCommandWrapper().getExtensions();
    checkForUndeclaredExtensions(suppliedExtensions);
    checkForRestrictedExtensions(suppliedExtensions);
    checkForDuplicateExtensions(suppliedExtensionInstances, suppliedExtensions);
    checkForUnimplementedExtensions(suppliedExtensionInstances, implementedExtensions);
  }

  private void checkForUndeclaredExtensions(
      ImmutableSet<Class<? extends CommandExtension>> suppliedExtensions)
          throws UndeclaredServiceExtensionException {
    ImmutableSet.Builder<String> suppliedUris = new ImmutableSet.Builder<>();
    for (Class<? extends CommandExtension> extension : suppliedExtensions) {
      suppliedUris.add(getCommandExtensionUri(extension));
    }
    Set<String> declaredUris = sessionMetadata.getServiceExtensionUris();
    Set<String> undeclaredUris = difference(suppliedUris.build(), declaredUris);
    if (undeclaredUris.isEmpty()) {
      return;
    }
    Set<String> undeclaredUrisThatError = intersection(undeclaredUris, UNDECLARED_URIS_DENYLIST);
    if (!undeclaredUrisThatError.isEmpty()) {
      throw new UndeclaredServiceExtensionException(undeclaredUrisThatError);
    }
    logger.atInfo().log(
        "Client %s is attempting to run %s without declaring URIs %s on login",
        registrarId, flowClass.getSimpleName(), undeclaredUris);
  }

  private static final ImmutableSet<EppRequestSource> ALLOWED_METADATA_EPP_REQUEST_SOURCES =
      ImmutableSet.of(EppRequestSource.TOOL, EppRequestSource.BACKEND);

  private void checkForRestrictedExtensions(
      ImmutableSet<Class<? extends CommandExtension>> suppliedExtensions)
      throws OnlyToolCanPassMetadataException, UnauthorizedForSuperuserExtensionException {
    if (suppliedExtensions.contains(MetadataExtension.class)
        && !ALLOWED_METADATA_EPP_REQUEST_SOURCES.contains(eppRequestSource)) {
      throw new OnlyToolCanPassMetadataException();
    }
    // Can't use suppliedExtension.contains() here because the SuperuserExtension has child classes.
    for (Class<? extends CommandExtension> suppliedExtension : suppliedExtensions) {
      if (SuperuserExtension.class.isAssignableFrom(suppliedExtension)
          && (!eppRequestSource.equals(EppRequestSource.TOOL) || !isSuperuser)) {
        throw new UnauthorizedForSuperuserExtensionException();
      }
    }
  }

  private static void checkForDuplicateExtensions(
      ImmutableList<CommandExtension> suppliedExtensionInstances,
      ImmutableSet<Class<? extends CommandExtension>> implementedExtensions)
          throws UnsupportedRepeatedExtensionException {
    for (Class<? extends CommandExtension> implemented : implementedExtensions) {
      if ((int)
              suppliedExtensionInstances
                  .stream()
                  .filter(implemented::isInstance)
                  .map(implemented::cast)
                  .count()
          > 1) {
        throw new UnsupportedRepeatedExtensionException();
      }
    }
  }

  private static void checkForUnimplementedExtensions(
      ImmutableList<CommandExtension> suppliedExtensionInstances,
      ImmutableSet<Class<? extends CommandExtension>> implementedExtensionClasses)
          throws UnimplementedExtensionException {
    ImmutableSet.Builder<Class<? extends CommandExtension>> unimplementedExtensionsBuilder =
        new ImmutableSet.Builder<>();
    for (final CommandExtension instance : suppliedExtensionInstances) {
      if (implementedExtensionClasses
          .stream()
          .noneMatch(implementedExtensionClass -> implementedExtensionClass.isInstance(instance))) {
        unimplementedExtensionsBuilder.add(instance.getClass());
      }
    }
    ImmutableSet<Class<? extends CommandExtension>> unimplementedExtensions =
        unimplementedExtensionsBuilder.build();
    if (!unimplementedExtensions.isEmpty()) {
      logger.atInfo().log("Unimplemented extensions: %s", unimplementedExtensions);
      throw new UnimplementedExtensionException();
    }
  }

  /** Service extension(s) must be declared at login. */
  public static class UndeclaredServiceExtensionException extends CommandUseErrorException {
    public UndeclaredServiceExtensionException(Set<String> undeclaredUris) {
      super(String.format("Service extension(s) must be declared at login: %s",
            Joiner.on(", ").join(undeclaredUris)));
    }
  }

  /** Unsupported repetition of an extension. */
  static class UnsupportedRepeatedExtensionException extends SyntaxErrorException {
    public UnsupportedRepeatedExtensionException() {
      super("Unsupported repetition of an extension");
    }
  }
}
