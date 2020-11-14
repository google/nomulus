// Copyright 2020 The Nomulus Authors. All Rights Reserved.
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

import com.google.appengine.api.datastore.Key;
import google.registry.persistence.DomainHistoryVKey;
import javax.annotation.Nullable;

/** Translator factory for {@link DomainHistoryVKey}. */
public class DomainHistoryVKeyTranslatorFactory
    extends AbstractSimpleTranslatorFactory<DomainHistoryVKey, Key> {

  public DomainHistoryVKeyTranslatorFactory() {
    super(DomainHistoryVKey.class);
  }

  @Override
  SimpleTranslator<DomainHistoryVKey, Key> createTranslator() {
    return new SimpleTranslator<DomainHistoryVKey, Key>() {

      @Nullable
      @Override
      public DomainHistoryVKey loadValue(@Nullable Key datastoreValue) {
        return datastoreValue == null
            ? null
            : DomainHistoryVKey.create(com.googlecode.objectify.Key.create(datastoreValue));
      }

      @Nullable
      @Override
      public Key saveValue(@Nullable DomainHistoryVKey pojoValue) {
        return pojoValue == null ? null : pojoValue.getOfyKey().getRaw();
      }
    };
  }
}
