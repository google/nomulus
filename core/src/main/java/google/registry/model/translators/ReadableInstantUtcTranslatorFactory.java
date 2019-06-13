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
import com.googlecode.objectify.impl.TypeUtils;
import com.googlecode.objectify.impl.translate.CreateContext;
import com.googlecode.objectify.impl.translate.LoadContext;
import com.googlecode.objectify.impl.translate.SaveContext;
import com.googlecode.objectify.impl.translate.ValueTranslator;
import com.googlecode.objectify.impl.translate.ValueTranslatorFactory;
import com.googlecode.objectify.repackaged.gentyref.GenericTypeReflector;
import java.lang.reflect.Constructor;
import java.lang.reflect.Type;
import java.util.Date;
import org.joda.time.DateTimeZone;
import org.joda.time.ReadableInstant;

/**
 * Stores Joda {@link ReadableInstant} types ({@code DateTime}, etc) as a {@link java.util.Date}.
 *
 * <p>This is a fork of the {@code ReadableInstantTranslatorFactory} that comes bundled with
 * Objectify.  The original reifies a {@link ReadableInstant} using the machine's local time
 * zone. This version always uses UTC.
 */
public class ReadableInstantUtcTranslatorFactory
    extends ValueTranslatorFactory<ReadableInstant, Date> {

  public ReadableInstantUtcTranslatorFactory() {
    super(ReadableInstant.class);
  }

  @Override
  protected ValueTranslator<ReadableInstant, Date> createSafe(
      Path path, Property property, Type type, CreateContext ctx) {
    final Class<?> clazz = GenericTypeReflector.erase(type);

    return new ValueTranslator<ReadableInstant, Date>(path, Date.class) {
      @Override
      protected ReadableInstant loadValue(Date value, LoadContext ctx) {
        // All the Joda instants have a constructor that will take a Date and timezone.
        Constructor<?> ctor = TypeUtils.getConstructor(clazz, Object.class, DateTimeZone.class);
        return (ReadableInstant) TypeUtils.newInstance(ctor, value, DateTimeZone.UTC);
      }

      @Override
      protected Date saveValue(ReadableInstant value, SaveContext ctx) {
        return value.toInstant().toDate();
      }
    };
  }
}
