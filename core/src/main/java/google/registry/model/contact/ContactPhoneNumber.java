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
import google.registry.model.eppcommon.PhoneNumber;
import javax.persistence.Embeddable;

/**
 * EPP Contact Phone Number
 *
 * <p>This class is embedded inside a {@link ContactResource} hold the phone number of an EPP
 * contact. The fields are all defined in the parent class {@link PhoneNumber}, but the subclass is
 * still necessary to pick up the contact namespace.
 *
 * @see ContactResource
 */
@Embed
@Embeddable
public class ContactPhoneNumber extends PhoneNumber {

  /** Builder for {@link ContactPhoneNumber}. */
  public static class Builder extends PhoneNumber.Builder<ContactPhoneNumber> {}
}
