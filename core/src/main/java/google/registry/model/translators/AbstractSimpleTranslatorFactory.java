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

import com.googlecode.objectify.impl.Path;
import com.googlecode.objectify.impl.Property;
import com.googlecode.objectify.impl.translate.CreateContext;
import com.googlecode.objectify.impl.translate.LoadContext;
import com.googlecode.objectify.impl.translate.SaveContext;
import com.googlecode.objectify.impl.translate.ValueTranslator;
import com.googlecode.objectify.impl.translate.ValueTranslatorFactory;
import google.registry.util.TypeUtils.TypeInstantiator;
import java.lang.reflect.Type;

/** Common boilerplate for translator factories. */
public abstract class AbstractSimpleTranslatorFactory<P, D> extends ValueTranslatorFactory<P, D> {

  public AbstractSimpleTranslatorFactory(Class<P> clazz) {
    super(clazz);
  }

  @Override
  protected final ValueTranslator<P, D> createSafe(
      Path path, Property property, Type type, CreateContext ctx) {
    return new ValueTranslator<P, D>(path, new TypeInstantiator<D>(getClass()){}.getExactType()) {

      SimpleTranslator<P, D> simpleTranslator = createTranslator();

      @Override
      protected P loadValue(D datastoreValue, LoadContext ctx) {
        return simpleTranslator.loadValue(datastoreValue);
      }

      @Override
      protected D saveValue(P pojoValue, SaveContext ctx) {
        return simpleTranslator.saveValue(pojoValue);
      }
    };
  }

  /** Translator with reduced boilerplate. */
  interface SimpleTranslator<P, D> {
    P loadValue(D datastoreValue);

    D saveValue(P pojoValue);
  }

  abstract SimpleTranslator<P, D> createTranslator();
}
