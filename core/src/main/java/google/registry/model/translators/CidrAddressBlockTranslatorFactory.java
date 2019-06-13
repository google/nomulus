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

import google.registry.util.CidrAddressBlock;

/** Stores {@link CidrAddressBlock} as a canonicalized string. */
public class CidrAddressBlockTranslatorFactory
    extends AbstractSimpleTranslatorFactory<CidrAddressBlock, String> {

  public CidrAddressBlockTranslatorFactory() {
    super(CidrAddressBlock.class);
  }

  @Override
  SimpleTranslator<CidrAddressBlock, String> createTranslator() {
    return new SimpleTranslator<CidrAddressBlock, String>(){
      @Override
      public CidrAddressBlock loadValue(String datastoreValue) {
        return CidrAddressBlock.create(datastoreValue);
      }

      @Override
      public String saveValue(CidrAddressBlock pojoValue) {
        return pojoValue.toString();
      }};
  }
}
