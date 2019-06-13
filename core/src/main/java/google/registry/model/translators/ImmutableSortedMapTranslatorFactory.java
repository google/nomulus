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

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.googlecode.objectify.repackaged.gentyref.GenericTypeReflector.erase;
import static com.googlecode.objectify.repackaged.gentyref.GenericTypeReflector.getTypeParameter;
import static google.registry.util.CollectionUtils.nullToEmpty;

import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Ordering;
import com.google.common.reflect.TypeToken;
import com.googlecode.objectify.impl.Node;
import com.googlecode.objectify.impl.Path;
import com.googlecode.objectify.impl.Property;
import com.googlecode.objectify.impl.translate.CreateContext;
import com.googlecode.objectify.impl.translate.ListNodeTranslator;
import com.googlecode.objectify.impl.translate.LoadContext;
import com.googlecode.objectify.impl.translate.SaveContext;
import com.googlecode.objectify.impl.translate.SkipException;
import com.googlecode.objectify.impl.translate.Translator;
import com.googlecode.objectify.impl.translate.TranslatorFactory;
import com.googlecode.objectify.impl.translate.TranslatorRegistry;
import java.lang.reflect.Type;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * Abstract Objectify translator for {@link ImmutableSortedMap} fields.
 *
 * <p>This class should be extended for each set of concrete key/value types you wish to support.
 * This translator will only apply to {@code ImmutableSortedMap} model fields that have precicely
 * the same type parameters you specified.
 *
 * <p>This translator serves a similar purpose to
 * {@link com.googlecode.objectify.impl.translate.MapifyTranslatorFactory @Mapify}. Except this
 * maintains perfect immutability of the field value. Subclasses may override the
 * {@link #transformBeforeSave(ImmutableSortedMap)} methods to perform mutation on a per-concrete
 * type basis. This abstraction is also more readable than {@code @Mapify} because it shifts the
 * boilerplate into translator magic, rather than convoluting model data structures.
 *
 * <h3>Entity Data Layout</h3>
 *
 * <p>For example, if you had an {@code ImmutableSortedMap<String, String>} on a field named
 * {@code field}, then this would look like:<pre>   {@code
 *
 *   field.key: key1 -> key2
 *   field.value: value1 -> value2}</pre>
 *
 * <p>If you had an {@code ImmutableSortedMap<String, EmbeddedClass>} on a field named
 * {@code field}, where {@code EmbeddedClass} defines two {@code foo} and {@code bar} fields, then
 * the embedded properties might look like:<pre>   {@code
 *
 *   field.key: key1 -> key2
 *   field.value.foo: foo1 -> foo2
 *   field.value.bar: bar1 -> bar2}</pre>
 *
 * @param <K> key type for sorted map which must be {@link Comparable}
 * @param <V> value type for sorted map
 */
abstract class ImmutableSortedMapTranslatorFactory<K extends Comparable<? super K>, V>
    implements TranslatorFactory<ImmutableSortedMap<K, V>> {

  private final TypeToken<K> keyType = new TypeToken<K>(getClass()) {};
  private final TypeToken<V> valueType = new TypeToken<V>(getClass()) {};
  private final String keyProperty;
  private final String valueProperty;

  ImmutableSortedMapTranslatorFactory() {
    this("key", "value");
  }

  /** Constructs a instance that's compatible with models migrated from {@code @Mapify}. */
  ImmutableSortedMapTranslatorFactory(String keyProperty, String valueProperty) {
    this.keyProperty = checkNotNull(keyProperty);
    this.valueProperty = checkNotNull(valueProperty);
  }

  /** Allows for changing the field data structure before it's written to the raw entity object. */
  ImmutableSortedMap<K, V> transformBeforeSave(ImmutableSortedMap<K, V> map) {
    return map;
  }

  @Override
  public final Translator<ImmutableSortedMap<K, V>>
      create(Path path, Property property, Type type, CreateContext ctx) {
    if (!ImmutableSortedMap.class.equals(erase(type))) {
      return null;  // skip me and try to find another matching translator
    }
    Type fieldKeyType = getTypeParameter(type, ImmutableSortedMap.class.getTypeParameters()[0]);
    Type fieldValueType = getTypeParameter(type, ImmutableSortedMap.class.getTypeParameters()[1]);
    if (fieldKeyType == null || fieldValueType == null) {
      return null;  // no type information is available
    }
    if (!keyType.isSupertypeOf(fieldKeyType) || !valueType.isSupertypeOf(fieldValueType)) {
      return null;  // this ImmutableSortedMap does not have the same concrete component types
    }
    ctx.enterCollection(path);
    ctx.enterEmbed(path);
    try {
      // The component types can also be translated by Objectify!
      TranslatorRegistry translators = ctx.getFactory().getTranslators();
      final Translator<K> keyTranslator =
          translators.create(path.extend(keyProperty), property, fieldKeyType, ctx);
      final Translator<V> valueTranslator =
          translators.create(path.extend(valueProperty), property, fieldValueType, ctx);
      return new ListNodeTranslator<ImmutableSortedMap<K, V>>() {
        @Override
        protected ImmutableSortedMap<K, V> loadList(Node node, LoadContext ctx) {
          ImmutableSortedMap.Builder<K, V> map =
              new ImmutableSortedMap.Builder<>(Ordering.natural());
          for (Node child : node) {
            try {
              map.put(keyTranslator.load(child.get(keyProperty), ctx),
                  valueTranslator.load(child.get(valueProperty), ctx));
            } catch (SkipException e) {
              // no problem, just skip that one
            }
          }
          return map.build();
        }

        @Override
        protected Node saveList(
            @Nullable ImmutableSortedMap<K, V> mapFromPojo,
            Path path,
            boolean index,
            SaveContext ctx) {
          checkState(!index, "At path %s: Index not allowed", path);
          ImmutableSortedMap<K, V> mapToSave = transformBeforeSave(
              ImmutableSortedMap.copyOfSorted(nullToEmpty(mapFromPojo)));
          if (mapToSave.isEmpty()) {
            throw new SkipException();  // Datastore doesn't store empty lists
          }
          Node node = new Node(path);
          for (Map.Entry<K, V> entry : mapToSave.entrySet()) {
            Node item = new Node(path);
            item.put(keyProperty,
                keyTranslator.save(entry.getKey(), path.extend(keyProperty), index, ctx));
            item.put(valueProperty,
                valueTranslator.save(entry.getValue(), path.extend(valueProperty), index, ctx));
            node.addToList(item);
          }
          return node;
        }
      };
    } finally {
      ctx.exitEmbed();
      ctx.exitCollection();
    }
  }
}
