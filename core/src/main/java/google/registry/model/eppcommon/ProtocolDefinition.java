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
import jakarta.xml.bind.annotation.XmlSchema;
import java.util.EnumSet;

/** Constants that define the EPP protocol version we support. */
public class ProtocolDefinition {
  public static final String VERSION = "1.0";

  public static final String LANGUAGE = "en";

  public static final ImmutableSet<String> SUPPORTED_OBJECT_SERVICES =
      ImmutableSet.of(
          "urn:ietf:params:xml:ns:host-1.0",
          "urn:ietf:params:xml:ns:domain-1.0",
          "urn:ietf:params:xml:ns:contact-1.0");

  /** Enums repesenting valid service extensions that are recognized by the server. */
  public enum ServiceExtension {
    LAUNCH_EXTENSION_1_0(LaunchCreateExtension.class, null, true),
    REDEMPTION_GRACE_PERIOD_1_0(RgpUpdateExtension.class, null, true),
    SECURE_DNS_1_1(SecDnsCreateExtension.class, null, true),
    FEE_0_6(FeeCheckCommandExtensionV06.class, FeeCheckResponseExtensionV06.class, true),
    FEE_0_11(FeeCheckCommandExtensionV11.class, FeeCheckResponseExtensionV11.class, true),
    FEE_0_12(FeeCheckCommandExtensionV12.class, FeeCheckResponseExtensionV12.class, true),
    FEE_STD_1_0(FeeCheckCommandExtensionStdV1.class, FeeCheckResponseExtensionStdV1.class, true),
    METADATA_1_0(MetadataExtension.class, null, false);

    private final Class<? extends CommandExtension> commandExtensionClass;
    private final Class<? extends ResponseExtension> responseExtensionClass;
    private final String uri;
    private final boolean visible;

    ServiceExtension(
        Class<? extends CommandExtension> commandExtensionClass,
        Class<? extends ResponseExtension> responseExtensionClass,
        boolean visible) {
      this.commandExtensionClass = commandExtensionClass;
      this.responseExtensionClass = responseExtensionClass;
      this.uri = getCommandExtensionUri(commandExtensionClass);
      this.visible = visible;
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

    public boolean getVisible() {
      return visible;
    }

    /** Returns the namespace URI of the command extension class. */
    public static String getCommandExtensionUri(Class<? extends CommandExtension> clazz) {
      return clazz.getPackage().getAnnotation(XmlSchema.class).namespace();
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
  private static final ImmutableSet<String> visibleServiceExtensionUris =
      EnumSet.allOf(ServiceExtension.class)
          .stream()
          .filter(ServiceExtension::getVisible)
          .map(ServiceExtension::getUri)
          .collect(toImmutableSet());

  /** Return the set of all visible service extension URIs. */
  public static ImmutableSet<String> getVisibleServiceExtensionUris() {
    return visibleServiceExtensionUris;
  }
}
