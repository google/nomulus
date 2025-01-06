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

import google.registry.model.Buildable.GenericBuilder;
import google.registry.model.ImmutableObject;
import jakarta.xml.bind.annotation.XmlTransient;

/**
 * A launch extension which can be passed in to domain update and delete, and also returned from
 * domain create.
 */
@XmlTransient
public abstract class LaunchExtension extends ImmutableObject {

  /** The launch phase that this domain application was created in. */
  LaunchPhase phase;

  public LaunchPhase getPhase() {
    return phase;
  }

  /** A builder for constructing {@link LaunchExtension}. */
  public static class Builder<T extends LaunchExtension, B extends Builder<?, ?>>
      extends GenericBuilder<T, B> {

    public B setPhase(LaunchPhase phase) {
      getInstance().phase = phase;
      return thisCastToDerived();
    }
  }
}
