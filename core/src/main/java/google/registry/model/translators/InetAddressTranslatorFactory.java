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

package google.registry.model.translators;

import com.google.common.net.InetAddresses;
import java.net.InetAddress;

/** Stores {@link InetAddress} as a canonicalized string. */
public class InetAddressTranslatorFactory
    extends AbstractSimpleTranslatorFactory<InetAddress, String> {

  public InetAddressTranslatorFactory() {
    super(InetAddress.class);
  }

  @Override
  SimpleTranslator<InetAddress, String> createTranslator() {
    return new SimpleTranslator<InetAddress, String>() {
      @Override
      public InetAddress loadValue(String datastoreValue) {
        return InetAddresses.forString(datastoreValue);
      }

      @Override
      public String saveValue(InetAddress pojoValue) {
        return pojoValue.getHostAddress();
      }};
  }
}
