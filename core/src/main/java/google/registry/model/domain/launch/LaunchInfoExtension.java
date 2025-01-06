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

import jakarta.xml.bind.annotation.XmlAttribute;
import jakarta.xml.bind.annotation.XmlRootElement;

/**
 * An XML data object that represents a launch extension that may be present on EPP domain info
 * commands.
 */
@XmlRootElement(name = "info")
public class LaunchInfoExtension extends LaunchExtension {

  /** Whether or not to include mark information in the response. */
  @XmlAttribute
  Boolean includeMark;

  public Boolean getIncludeMark() {
    return includeMark;
  }
}
