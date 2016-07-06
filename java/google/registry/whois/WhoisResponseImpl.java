// Copyright 2016 The Domain Registry Authors. All Rights Reserved.
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

package google.registry.whois;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Strings.isNullOrEmpty;
import static com.google.common.html.HtmlEscapers.htmlEscaper;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.base.Supplier;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.Iterables;
import com.google.common.collect.Ordering;
import com.google.common.io.Resources;

import google.registry.config.RegistryEnvironment;
import google.registry.model.eppcommon.Address;
import google.registry.model.registrar.Registrar;
import google.registry.util.Idn;
import google.registry.xml.UtcDateTimeAdapter;

import org.joda.time.DateTime;

import java.io.IOException;
import java.net.URL;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import javax.annotation.Nullable;

/** Base class for responses to WHOIS queries. */
abstract class WhoisResponseImpl implements WhoisResponse {

  private static final RegistryEnvironment ENVIRONMENT = RegistryEnvironment.get();

  /** Legal disclaimer that is appended to all WHOIS responses. */
  private static final String DISCLAIMER = ENVIRONMENT.config().getWhoisRequestDisclaimer();

  /** Field name for ICANN problem reporting URL appended to all WHOIS responses. */
  private static final String ICANN_REPORTING_URL_FIELD =
      "URL of the ICANN WHOIS Data Problem Reporting System";

  /** ICANN problem reporting URL appended to all WHOIS responses. */
  private static final String ICANN_REPORTING_URL = "http://wdprs.internic.net/";

  private static final Registrar EMPTY_REGISTRAR = new Supplier<Registrar>() {
      @Override
      public Registrar get() {
        // Use Type.TEST here to avoid requiring an IANA ID (the type does not appear in WHOIS).
        return new Registrar.Builder().setType(Registrar.Type.TEST).build();
      }}.get();

  /** The time at which this response was created. */
  private final DateTime timestamp;

  WhoisResponseImpl(DateTime timestamp) {
    this.timestamp = checkNotNull(timestamp, "timestamp");
  }

  @Override
  public DateTime getTimestamp() {
    return timestamp;
  }

  /**
   * Translates a hostname to its unicode representation if desired.
   *
   * @param hostname is assumed to be in its canonical ASCII form from the database.
   */
  static String maybeFormatHostname(String hostname, boolean preferUnicode) {
    return preferUnicode ? Idn.toUnicode(hostname) : hostname;
  }

  static <T> T chooseByUnicodePreference(
      boolean preferUnicode, @Nullable T localized, @Nullable T internationalized) {
    if (preferUnicode) {
      return Optional.fromNullable(localized).or(Optional.fromNullable(internationalized)).orNull();
    } else {
      return Optional.fromNullable(internationalized).or(Optional.fromNullable(localized)).orNull();
    }
  }

  /** Writer for outputting data in the WHOIS format. */
  abstract static class Emitter<E extends Emitter<E>> {

    private final StringBuilder stringBuilder = new StringBuilder();

    @SuppressWarnings("unchecked")
    private E thisCastToDerived() {
      return (E) this;
    }

    E emitNewline() {
      stringBuilder.append("\r\n");
      return thisCastToDerived();
    }

    /**
     * Helper method that loops over a set of values and calls {@link #emitField}. This method will
     * turn each value into a string using the provided callback and then sort those strings so the
     * textual output is deterministic (which is important for unit tests). The ideal solution would
     * be to use {@link java.util.SortedSet} but that would require reworking the models.
     */
    <T> E emitSet(String title, Set<T> values, Function<T, String> transform) {
      return emitList(title, FluentIterable
          .from(values)
          .transform(transform)
          .toSortedList(Ordering.natural()));
    }

    /** Helper method that loops over a list of values and calls {@link #emitField}. */
    E emitList(String title, Iterable<String> values) {
      for (String value : values) {
        emitField(title, value);
      }
      return thisCastToDerived();
    }

    /** Emit the field name and value followed by a newline. */
    E emitField(String name, @Nullable String value) {
      stringBuilder.append(cleanse(name)).append(':');
      if (!isNullOrEmpty(value)) {
        stringBuilder.append(' ').append(cleanse(value));
      }
      return emitNewline();
    }

    /** Emit a multi-part field name and value followed by a newline. */
    E emitField(String... namePartsAndValue) {
      List<String> parts = Arrays.asList(namePartsAndValue);
      return emitField(
          Joiner.on(' ').join(parts.subList(0, parts.size() - 1)), Iterables.getLast(parts));
    }

    /** Emit a contact address. */
    E emitAddress(@Nullable String prefix, @Nullable Address address) {
      prefix = isNullOrEmpty(prefix) ? "" : prefix + " ";
      if (address != null) {
        emitList(prefix + "Street", address.getStreet());
        emitField(prefix + "City", address.getCity());
        emitField(prefix + "State/Province", address.getState());
        emitField(prefix + "Postal Code", address.getZip());
        emitField(prefix + "Country", address.getCountryCode());
      }
      return thisCastToDerived();
    }

    /** Returns raw text that should be appended to the end of ALL WHOIS responses. */
    E emitLastUpdated(DateTime timestamp) {
      // We are assuming that our WHOIS database is always completely up to date, since it's
      // querying the live backend datastore.
      stringBuilder
          .append(">>> Last update of WHOIS database: ")
          .append(UtcDateTimeAdapter.getFormattedString(timestamp))
          .append(" <<<\r\n\r\n");
      return thisCastToDerived();
    }

    /** Returns raw text that should be appended to the end of ALL WHOIS responses. */
    E emitFooter() {
      emitField(ICANN_REPORTING_URL_FIELD, ICANN_REPORTING_URL);
      stringBuilder.append("\r\n").append(DISCLAIMER).append("\r\n");
      return thisCastToDerived();
    }

    /** Emits a string directly, followed by a newline. */
    protected E emitRawLine(String string) {
      stringBuilder.append(string);
      return emitNewline();
    }

    /**
     * Remove potentially dangerous stuff from WHOIS output fields.
     *
     * <ul>
     * <li>Remove ASCII control characters like {@code \n} which could be used to forge output.
     * <li>Escape HTML entities, just in case this gets injected poorly into a webpage.
     * </ul>
     */
    private String cleanse(String value) {
      return htmlEscaper().escape(value).replaceAll("[\\x00-\\x1f]", " ");
    }

    @Override
    public String toString() {
      return stringBuilder.toString();
    }
  }

  /** An emitter that needs no special logic. */
  static class BasicEmitter extends Emitter<BasicEmitter> {}

  /** Returns the registrar for this client id, or an empty registrar with null values. */
  static Registrar getRegistrar(@Nullable String clientId) {
    return Optional
        .fromNullable(clientId == null ? null : Registrar.loadByClientId(clientId))
        .or(EMPTY_REGISTRAR);
  }
}
