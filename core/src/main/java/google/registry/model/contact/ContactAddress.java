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

package google.registry.model.contact;

import com.googlecode.objectify.annotation.Embed;
import google.registry.model.eppcommon.Address;
import javax.persistence.Embeddable;

/**
 * EPP Contact Address
 *
 * <p>This class is embedded inside the {@link PostalInfo} of an EPP contact to hold its address.
 * The fields are all defined in parent class {@link Address}, but the subclass is still necessary
 * to pick up the contact namespace.
 *
 * <p>This does not implement {@code Overlayable} because it is intended to be bulk replaced on
 * update.
 *
 * @see PostalInfo
 */
@Embed
@Embeddable
public class ContactAddress extends Address {

  /** Builder for {@link ContactAddress}. */
  public static class Builder extends Address.Builder<ContactAddress> {}
}
