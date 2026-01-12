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

package google.registry.model.eppcommon;

import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static com.google.common.collect.Maps.uniqueIndex;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import google.registry.model.domain.fee06.FeeCheckCommandExtensionV06;
import google.registry.model.domain.fee06.FeeCheckResponseExtensionV06;
import google.registry.model.domain.fee11.FeeCheckCommandExtensionV11;
import google.registry.model.domain.fee11.FeeCheckResponseExtensionV11;
import google.registry.model.domain.fee12.FeeCheckCommandExtensionV12;
import google.registry.model.domain.fee12.FeeCheckResponseExtensionV12;
import google.registry.model.domain.feestdv1.FeeCheckCommandExtensionStdV1;
import google.registry.model.domain.feestdv1.FeeCheckResponseExtensionStdV1;
import google.registry.model.domain.launch.LaunchCreateExtension;
import google.registry.model.domain.metadata.MetadataExtension;
import google.registry.model.domain.rgp.RgpUpdateExtension;
import google.registry.model.domain.secdns.SecDnsCreateExtension;
import google.registry.model.eppinput.EppInput.CommandExtension;
import google.registry.model.eppoutput.EppResponse.ResponseExtension;
import google.registry.util.NonFinalForTesting;
import google.registry.util.RegistryEnvironment;
import jakarta.xml.bind.annotation.XmlSchema;
import java.util.EnumSet;

/** Constants that define the EPP protocol version we support. */
public class ProtocolDefinition {
  public static final String VERSION = "1.0";

  public static final String LANGUAGE = "en";

  public static final ImmutableSet<String> SUPPORTED_OBJECT_SERVICES =
      ImmutableSet.of("urn:ietf:params:xml:ns:host-1.0", "urn:ietf:params:xml:ns:domain-1.0");

  /** Enum representing which environments should have which service extensions enabled. */
  private enum ServiceExtensionVisibility {
    ALL,
    ONLY_IN_PRODUCTION,
    ONLY_IN_NON_PRODUCTION,
    NONE
  }

  /** Enum representing valid service extensions that are recognized by the server. */
  public enum ServiceExtension {
    LAUNCH_EXTENSION_1_0(LaunchCreateExtension.class, null, ServiceExtensionVisibility.ALL),
    REDEMPTION_GRACE_PERIOD_1_0(RgpUpdateExtension.class, null, ServiceExtensionVisibility.ALL),
    SECURE_DNS_1_1(SecDnsCreateExtension.class, null, ServiceExtensionVisibility.ALL),
    FEE_0_6(
        FeeCheckCommandExtensionV06.class,
        FeeCheckResponseExtensionV06.class,
        ServiceExtensionVisibility.ONLY_IN_PRODUCTION),
    FEE_0_11(
        FeeCheckCommandExtensionV11.class,
        FeeCheckResponseExtensionV11.class,
        ServiceExtensionVisibility.ONLY_IN_PRODUCTION),
    FEE_0_12(
        FeeCheckCommandExtensionV12.class,
        FeeCheckResponseExtensionV12.class,
        ServiceExtensionVisibility.ONLY_IN_PRODUCTION),
    FEE_1_00(
        FeeCheckCommandExtensionStdV1.class,
        FeeCheckResponseExtensionStdV1.class,
        ServiceExtensionVisibility.ONLY_IN_NON_PRODUCTION),
    METADATA_1_0(MetadataExtension.class, null, ServiceExtensionVisibility.NONE);

    private final Class<? extends CommandExtension> commandExtensionClass;
    private final Class<? extends ResponseExtension> responseExtensionClass;
    private final String uri;
    private final ServiceExtensionVisibility visibility;

    ServiceExtension(
        Class<? extends CommandExtension> commandExtensionClass,
        Class<? extends ResponseExtension> responseExtensionClass,
        ServiceExtensionVisibility visibility) {
      this.commandExtensionClass = commandExtensionClass;
      this.responseExtensionClass = responseExtensionClass;
      this.uri = getCommandExtensionUri(commandExtensionClass);
      this.visibility = visibility;
    }

    public Class<? extends CommandExtension> getCommandExtensionClass() {
      return commandExtensionClass;
    }

    public Class<? extends ResponseExtension> getResponseExtensionClass() {
      return responseExtensionClass;
    }

    public String getUri() {
      return uri;
    }

    /** Returns the namespace URI of the command extension class. */
    public static String getCommandExtensionUri(Class<? extends CommandExtension> clazz) {
      return clazz.getPackage().getAnnotation(XmlSchema.class).namespace();
    }

    public boolean isVisible() {
      return switch (visibility) {
        case ALL -> true;
        case ONLY_IN_PRODUCTION -> RegistryEnvironment.get().equals(RegistryEnvironment.PRODUCTION);
        case ONLY_IN_NON_PRODUCTION ->
            !RegistryEnvironment.get().equals(RegistryEnvironment.PRODUCTION);
        case NONE -> false;
      };
    }
  }

  /**
   * Converts a service extension enum to its URI.
   *
   *  <p>This stores a map from URI back to the service extension enum.
   */
  private static final ImmutableMap<String, ServiceExtension> serviceExtensionByUri =
      uniqueIndex(EnumSet.allOf(ServiceExtension.class), ServiceExtension::getUri);

  /** Returns the service extension enum associated with a URI, or null if none are associated. */
  public static ServiceExtension getServiceExtensionFromUri(String uri) {
    return serviceExtensionByUri.get(uri);
  }

  /** A set of all the visible extension URIs. */
  // TODO(gbrodman): make this final when we can actually remove the old fee extensions and aren't
  // relying on switching by environment
  @NonFinalForTesting private static ImmutableSet<String> visibleServiceExtensionUris;

  static {
    reloadServiceExtensionUris();
  }

  /** Return the set of all visible service extension URIs. */
  public static ImmutableSet<String> getVisibleServiceExtensionUris() {
    return visibleServiceExtensionUris;
  }

  @VisibleForTesting
  public static void reloadServiceExtensionUris() {
    visibleServiceExtensionUris =
        EnumSet.allOf(ServiceExtension.class).stream()
            .filter(ServiceExtension::isVisible)
            .map(ServiceExtension::getUri)
            .collect(toImmutableSet());
  }
}
