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
 * Interface for individual fee extension items in Check commands. These are derived from the more
 * general query items (which cover Info commands as well), but may also contain a domain name,
 * depending on the version of the fee extension.
 */
@XmlTransient
public abstract class FeeCheckCommandExtensionItem extends FeeQueryCommandExtensionItem {

  /** True if this version of the fee extension supports domain names in Check items. */
  public abstract boolean isDomainNameSupported();

  /** The domain name being checked; throws an exception if domain names are not supported. */
  public abstract String getDomainName();

  /** Create a builder for a matching fee check response item. */
  public abstract FeeCheckResponseExtensionItem.Builder<?> createResponseBuilder();
}
