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

import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static java.util.function.Function.identity;

import com.google.appengine.api.datastore.Key;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import google.registry.persistence.BillingVKey.BillingEventVKey;
import google.registry.persistence.BillingVKey.BillingRecurrenceVKey;
import google.registry.persistence.DomainHistoryVKey;
import google.registry.persistence.EppHistoryVKey;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import javax.annotation.Nullable;

/** Translator factory for {@link EppHistoryVKey}. */
public class EppHistoryVKeyTranslatorFactory
    extends AbstractSimpleTranslatorFactory<EppHistoryVKey, Key> {

  public EppHistoryVKeyTranslatorFactory() {
    super(EppHistoryVKey.class);
  }

  // This map is used when we need to convert the raw Datastore key to its VKey instance. We have
  // one dedicated VKey class, e.g. DomainHistoryVKey, for each such kind of entity, and we need
  // a way to map the raw Datastore key to its VKey class. So, we use the kind path as the key of
  // the map, and the kind path is created by concatenating all the kind strings in a raw Datastore
  // key, e.g. the map key for ContactPollMessageVKey is "ContactResource/HistoryEntry/PollMessage".
  @VisibleForTesting
  static final ImmutableMap<String, Class<? extends EppHistoryVKey>> kindPathToVKeyClass =
      ImmutableSet.of(DomainHistoryVKey.class, BillingEventVKey.class, BillingRecurrenceVKey.class)
          .stream()
          .collect(toImmutableMap(EppHistoryVKeyTranslatorFactory::getKindPath, identity()));

  /**
   * Gets the kind path string for the given {@link Class}.
   *
   * <p>This method calls the getKindPath method on an instance of the given {@link Class} to get
   * the kind path string.
   */
  private static String getKindPath(Class<? extends EppHistoryVKey> clazz) {
    try {
      Constructor<?> constructor = clazz.getDeclaredConstructor();
      constructor.setAccessible(true);
      Object instance = constructor.newInstance();
      Method getKindPathMethod = EppHistoryVKey.class.getDeclaredMethod("getKindPath");
      getKindPathMethod.setAccessible(true);
      return (String) getKindPathMethod.invoke(instance);
    } catch (Throwable t) {
      throw new IllegalStateException(t);
    }
  }

  @Override
  SimpleTranslator<EppHistoryVKey, Key> createTranslator() {
    return new SimpleTranslator<EppHistoryVKey, Key>() {

      @Nullable
      @Override
      public EppHistoryVKey loadValue(@Nullable Key datastoreValue) {
        if (datastoreValue == null) {
          return null;
        } else {
          com.googlecode.objectify.Key<?> ofyKey =
              com.googlecode.objectify.Key.create(datastoreValue);
          String kindPath = EppHistoryVKey.createKindPath(ofyKey);
          if (kindPathToVKeyClass.containsKey(kindPath)) {
            Class<? extends EppHistoryVKey> vKeyClass = kindPathToVKeyClass.get(kindPath);
            try {
              Method createVKeyMethod =
                  vKeyClass.getDeclaredMethod("create", com.googlecode.objectify.Key.class);
              return (EppHistoryVKey) createVKeyMethod.invoke(null, ofyKey);
            } catch (NoSuchMethodException e) {
              throw new IllegalStateException(
                  "Missing static method create(com.googlecode.objectify.Key) on " + vKeyClass);
            } catch (IllegalAccessException | InvocationTargetException e) {
              throw new IllegalStateException("Error invoking createVKey on " + vKeyClass, e);
            }
          } else {
            throw new IllegalStateException(
                "Missing EppHistoryVKey implementation for kind path: " + kindPath);
          }
        }
      }

      @Nullable
      @Override
      public Key saveValue(@Nullable EppHistoryVKey pojoValue) {
        return pojoValue == null ? null : pojoValue.createOfyKey().getRaw();
      }
    };
  }
}
