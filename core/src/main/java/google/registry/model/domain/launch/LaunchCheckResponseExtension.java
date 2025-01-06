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

package google.registry.model.domain.launch;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import google.registry.model.ImmutableObject;
import google.registry.model.eppoutput.EppResponse.ResponseExtension;
import jakarta.xml.bind.annotation.XmlAttribute;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlRootElement;
import jakarta.xml.bind.annotation.XmlType;
import jakarta.xml.bind.annotation.XmlValue;

/**
 * An XML data object that represents a launch extension that may be present on the response to EPP
 * domain check commands.
 */
@XmlRootElement(name = "chkData")
@XmlType(propOrder = {"phase", "launchChecks"})
public class LaunchCheckResponseExtension extends ImmutableObject implements ResponseExtension {

  /** The launch phase that this domain check was run against. */
  LaunchPhase phase;

  /** Check responses. */
  @XmlElement(name = "cd")
  ImmutableList<LaunchCheck> launchChecks;

  @VisibleForTesting
  public LaunchPhase getPhase() {
    return phase;
  }

  @VisibleForTesting
  public ImmutableList<LaunchCheck> getChecks() {
    return launchChecks;
  }

  /** The response for a check on a single resource. */
  public static class LaunchCheck extends ImmutableObject {
    /** An element containing the name and availability of a resource. */
    LaunchCheckName name;

    /** A key used to generate a Trademark Claims Notice. Only returned on claims checks. */
    String claimKey;

    public static LaunchCheck create(LaunchCheckName name, String claimKey) {
      LaunchCheck instance = new LaunchCheck();
      instance.name = name;
      instance.claimKey = claimKey;
      return instance;
    }
  }

  /** Holds the name and availability of a checked resource. */
  public static class LaunchCheckName extends ImmutableObject {
    /** Whether the resource is available. */
    @XmlAttribute
    boolean exists;

    /** The name of the resource being checked. */
    @XmlValue
    String name;

    public static LaunchCheckName create(boolean exists, String name) {
      LaunchCheckName instance = new LaunchCheckName();
      instance.exists = exists;
      instance.name = name;
      return instance;
    }
  }

  public static LaunchCheckResponseExtension create(
      LaunchPhase phase, ImmutableList<LaunchCheck> launchChecks) {
    LaunchCheckResponseExtension instance = new LaunchCheckResponseExtension();
    instance.phase = phase;
    instance.launchChecks = launchChecks;
    return instance;
  }
}
