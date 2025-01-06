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
package google.registry.model;

import static com.google.common.collect.ImmutableSortedMap.toImmutableSortedMap;
import static com.google.common.collect.ImmutableSortedSet.toImmutableSortedSet;
import static com.google.common.collect.Ordering.natural;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator.Feature;
import com.google.common.collect.ImmutableSortedSet;
import google.registry.model.common.FeatureFlag.FeatureStatus;
import google.registry.model.common.TimedTransitionProperty;
import google.registry.model.domain.token.AllocationToken;
import google.registry.model.tld.Tld.TldState;
import google.registry.persistence.VKey;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import org.joda.money.CurrencyUnit;
import org.joda.money.Money;
import org.joda.time.DateTime;
import org.joda.time.Duration;

/** A collection of static utility classes/functions to convert entities to/from YAML files. */
public class EntityYamlUtils {

  /**
   * Returns a new {@link ObjectMapper} object that can be used to convert an entity to/from YAML.
   */
  public static ObjectMapper createObjectMapper() {
    SimpleModule module = new SimpleModule();
    module.addSerializer(Money.class, new MoneySerializer());
    module.addDeserializer(Money.class, new MoneyDeserializer());
    module.addSerializer(Duration.class, new DurationSerializer());
    module.addSerializer(TimedTransitionProperty.class, new TimedTransitionPropertySerializer());
    ObjectMapper mapper =
        JsonMapper.builder(new YAMLFactory().disable(Feature.WRITE_DOC_START_MARKER))
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .build();
    mapper.findAndRegisterModules().registerModule(module);
    return mapper;
  }

  /**
   * A custom serializer for String Set to sort the order and make YAML generation deterministic.
   */
  public static class SortedSetSerializer extends StdSerializer<Set<String>> {
    public SortedSetSerializer() {
      this(null);
    }

    public SortedSetSerializer(Class<Set<String>> t) {
      super(t);
    }

    @Override
    public void serialize(Set<String> value, JsonGenerator g, SerializerProvider provider)
        throws IOException {
      ImmutableSortedSet<String> sorted =
          value.stream()
              .collect(toImmutableSortedSet(String::compareTo)); // sort the entries into a new set
      g.writeStartArray();
      for (String entry : sorted) {
        g.writeString(entry);
      }
      g.writeEndArray();
    }
  }

  /** A custom serializer for Enum Set to sort the order and make YAML generation deterministic. */
  public static class SortedEnumSetSerializer extends StdSerializer<Set<Enum>> {
    public SortedEnumSetSerializer() {
      this(null);
    }

    public SortedEnumSetSerializer(Class<Set<Enum>> t) {
      super(t);
    }

    @Override
    public void serialize(Set<Enum> value, JsonGenerator g, SerializerProvider provider)
        throws IOException {
      ImmutableSortedSet<String> sorted =
          value.stream()
              .map(Enum::name)
              .collect(toImmutableSortedSet(String::compareTo)); // sort the entries into a new set
      g.writeStartArray();
      for (String entry : sorted) {
        g.writeString(entry);
      }
      g.writeEndArray();
    }
  }

  /** A custom JSON serializer for {@link Money}. */
  public static class MoneySerializer extends StdSerializer<Money> {

    public MoneySerializer() {
      this(null);
    }

    public MoneySerializer(Class<Money> t) {
      super(t);
    }

    @Override
    public void serialize(Money value, JsonGenerator gen, SerializerProvider provider)
        throws IOException {
      gen.writeStartObject();
      gen.writeStringField("currency", String.valueOf(value.getCurrencyUnit()));
      gen.writeNumberField("amount", value.getAmount());
      gen.writeEndObject();
    }
  }

  /** A custom JSON deserializer for {@link Money}. */
  public static class MoneyDeserializer extends StdDeserializer<Money> {

    public MoneyDeserializer() {
      this(null);
    }

    public MoneyDeserializer(Class<Money> t) {
      super(t);
    }

    static class MoneyJson {
      public String currency;
      public BigDecimal amount;
    }

    @Override
    public Money deserialize(JsonParser jp, DeserializationContext context) throws IOException {
      MoneyJson json = jp.readValueAs(MoneyJson.class);
      CurrencyUnit currencyUnit = CurrencyUnit.of(json.currency);
      return Money.of(currencyUnit, json.amount);
    }
  }

  /** A custom JSON serializer for {@link CurrencyUnit}. */
  public static class CurrencySerializer extends StdSerializer<CurrencyUnit> {

    public CurrencySerializer() {
      this(null);
    }

    public CurrencySerializer(Class<CurrencyUnit> t) {
      super(t);
    }

    @Override
    public void serialize(CurrencyUnit value, JsonGenerator gen, SerializerProvider provider)
        throws IOException {
      gen.writeString(value.getCode());
    }
  }

  /** A custom JSON deserializer for {@link CurrencyUnit}. */
  public static class CurrencyDeserializer extends StdDeserializer<CurrencyUnit> {

    public CurrencyDeserializer() {
      this(null);
    }

    public CurrencyDeserializer(Class<CurrencyUnit> t) {
      super(t);
    }

    @Override
    public CurrencyUnit deserialize(JsonParser jp, DeserializationContext context)
        throws IOException {
      String currencyCode = jp.readValueAs(String.class);
      return CurrencyUnit.of(currencyCode);
    }
  }

  /** A custom JSON serializer for a {@link Duration} object. */
  public static class DurationSerializer extends StdSerializer<Duration> {

    public DurationSerializer() {
      this(null);
    }

    public DurationSerializer(Class<Duration> t) {
      super(t);
    }

    @Override
    public void serialize(Duration value, JsonGenerator gen, SerializerProvider provider)
        throws IOException {
      gen.writeString(value.toString());
    }
  }

  /** A custom JSON serializer for an Optional of a {@link Duration} object. */
  public static class OptionalDurationSerializer extends StdSerializer<Optional<Duration>> {

    public OptionalDurationSerializer() {
      this(null);
    }

    public OptionalDurationSerializer(Class<Optional<Duration>> t) {
      super(t);
    }

    @Override
    public void serialize(Optional<Duration> value, JsonGenerator gen, SerializerProvider provider)
        throws IOException {
      if (value.isPresent()) {
        gen.writeString(value.get().toString());
      } else {
        gen.writeNull();
      }
    }
  }

  /** A custom JSON serializer for an Optional String. */
  public static class OptionalStringSerializer extends StdSerializer<Optional<String>> {

    public OptionalStringSerializer() {
      this(null);
    }

    public OptionalStringSerializer(Class<Optional<String>> t) {
      super(t);
    }

    @Override
    public void serialize(Optional<String> value, JsonGenerator gen, SerializerProvider provider)
        throws IOException {
      if (value.isPresent()) {
        gen.writeString(value.get());
      } else {
        gen.writeNull();
      }
    }
  }

  /** A custom JSON serializer for a list of {@link AllocationToken} VKeys. */
  public static class TokenVKeyListSerializer extends StdSerializer<List<VKey<AllocationToken>>> {

    public TokenVKeyListSerializer() {
      this(null);
    }

    public TokenVKeyListSerializer(Class<List<VKey<AllocationToken>>> t) {
      super(t);
    }

    @Override
    public void serialize(
        List<VKey<AllocationToken>> list, JsonGenerator gen, SerializerProvider provider)
        throws IOException {
      gen.writeStartArray();
      for (VKey<AllocationToken> vkey : list) {
        gen.writeString(vkey.getKey().toString());
      }
      gen.writeEndArray();
    }
  }

  /** A custom JSON deserializer for a list of {@link AllocationToken} VKeys. */
  public static class TokenVKeyListDeserializer
      extends StdDeserializer<List<VKey<AllocationToken>>> {

    public TokenVKeyListDeserializer() {
      this(null);
    }

    public TokenVKeyListDeserializer(Class<VKey<AllocationToken>> t) {
      super(t);
    }

    @Override
    public List<VKey<AllocationToken>> deserialize(JsonParser jp, DeserializationContext context)
        throws IOException {
      List<VKey<AllocationToken>> tokens = new ArrayList<>();
      String[] keyStrings = jp.readValueAs(String[].class);
      for (String token : keyStrings) {
        tokens.add(VKey.create(AllocationToken.class, token));
      }
      return tokens;
    }
  }

  /** A custom JSON serializer for a {@link TimedTransitionProperty} of {@link Enum} values. */
  public static class TimedTransitionPropertySerializer<E extends Enum<E>>
      extends StdSerializer<TimedTransitionProperty<E>> {

    TimedTransitionPropertySerializer() {
      super(null, true);
    }

    @Override
    public void serialize(
        TimedTransitionProperty<E> data,
        JsonGenerator jsonGenerator,
        SerializerProvider serializerProvider)
        throws IOException {
      jsonGenerator.writeObject(data.toValueMap());
    }
  }

  /** A custom JSON deserializer for a {@link TimedTransitionProperty} of {@link TldState}. */
  public static class TimedTransitionPropertyTldStateDeserializer
      extends StdDeserializer<TimedTransitionProperty<TldState>> {

    public TimedTransitionPropertyTldStateDeserializer() {
      this(null);
    }

    public TimedTransitionPropertyTldStateDeserializer(Class<TimedTransitionProperty<TldState>> t) {
      super(t);
    }

    @Override
    public TimedTransitionProperty<TldState> deserialize(
        JsonParser jp, DeserializationContext context) throws IOException {
      SortedMap<String, String> valueMap = jp.readValueAs(SortedMap.class);
      return TimedTransitionProperty.fromValueMap(
          valueMap.keySet().stream()
              .collect(
                  toImmutableSortedMap(
                      natural(), DateTime::parse, key -> TldState.valueOf(valueMap.get(key)))));
    }
  }

  /** A custom JSON deserializer for a {@link TimedTransitionProperty} of {@link Money}. */
  public static class TimedTransitionPropertyMoneyDeserializer
      extends StdDeserializer<TimedTransitionProperty<Money>> {

    public TimedTransitionPropertyMoneyDeserializer() {
      this(null);
    }

    public TimedTransitionPropertyMoneyDeserializer(Class<TimedTransitionProperty<Money>> t) {
      super(t);
    }

    @Override
    public TimedTransitionProperty<Money> deserialize(JsonParser jp, DeserializationContext context)
        throws IOException {
      SortedMap<String, LinkedHashMap<String, Object>> valueMap = jp.readValueAs(SortedMap.class);
      return TimedTransitionProperty.fromValueMap(
          valueMap.keySet().stream()
              .collect(
                  toImmutableSortedMap(
                      natural(),
                      DateTime::parse,
                      key ->
                          Money.of(
                              CurrencyUnit.of(valueMap.get(key).get("currency").toString()),
                              new BigDecimal(String.valueOf(valueMap.get(key).get("amount")))))));
    }
  }

  /** A custom JSON deserializer for a {@link TimedTransitionProperty} of {@link FeatureStatus}. */
  public static class TimedTransitionPropertyFeatureStatusDeserializer
      extends StdDeserializer<TimedTransitionProperty<FeatureStatus>> {

    public TimedTransitionPropertyFeatureStatusDeserializer() {
      this(null);
    }

    public TimedTransitionPropertyFeatureStatusDeserializer(
        Class<TimedTransitionProperty<FeatureStatus>> t) {
      super(t);
    }

    @Override
    public TimedTransitionProperty<FeatureStatus> deserialize(
        JsonParser jp, DeserializationContext context) throws IOException {
      SortedMap<String, String> valueMap = jp.readValueAs(SortedMap.class);
      return TimedTransitionProperty.fromValueMap(
          valueMap.keySet().stream()
              .collect(
                  toImmutableSortedMap(
                      natural(),
                      DateTime::parse,
                      key -> FeatureStatus.valueOf(valueMap.get(key)))));
    }
  }

  /** A custom JSON deserializer for a {@link CreateAutoTimestamp}. */
  public static class CreateAutoTimestampDeserializer extends StdDeserializer<CreateAutoTimestamp> {

    public CreateAutoTimestampDeserializer() {
      this(null);
    }

    public CreateAutoTimestampDeserializer(Class<CreateAutoTimestamp> t) {
      super(t);
    }

    @Override
    public CreateAutoTimestamp deserialize(JsonParser jp, DeserializationContext context)
        throws IOException {
      DateTime creationTime = jp.readValueAs(DateTime.class);
      return CreateAutoTimestamp.create(creationTime);
    }
  }
}
