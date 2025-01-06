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

package google.registry.model.domain.fee;

import jakarta.xml.bind.annotation.XmlTransient;

/**
 * Abstract class for individual fee extension items in Check responses. These are derived from the
 * more general query items (which cover Info responses as well), but may also contain a domain
 * name, depending on the version of the fee extension.
 */
@XmlTransient
public abstract class FeeCheckResponseExtensionItem extends FeeQueryResponseExtensionItem {

  /** Abstract builder for {@link FeeCheckResponseExtensionItem}. */
  public abstract static class Builder<T extends FeeCheckResponseExtensionItem>
      extends FeeQueryResponseExtensionItem.Builder<T, Builder<T>> {

    /** The name associated with the item. Has no effect if domain names are not supported. */
    public Builder<T> setDomainNameIfSupported(@SuppressWarnings("unused") String name) {
      return this;  // Default impl is a noop.
    }
  }
}

