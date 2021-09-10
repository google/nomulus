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

package google.registry.monitoring.whitebox;

import static com.google.common.base.Preconditions.checkArgument;

import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import google.registry.model.eppoutput.Result.Code;
import google.registry.model.tld.Registries;
import google.registry.util.Clock;
import java.util.Optional;
import org.joda.time.DateTime;

/** A value class for recording attributes of an EPP metric. */
@AutoValue
public abstract class EppMetric {

  public abstract DateTime getStartTimestamp();

  public abstract DateTime getEndTimestamp();

  public abstract Optional<String> getCommandName();

  public abstract Optional<String> getRegistrarId();

  public abstract Optional<String> getTld();

  public abstract Optional<Code> getStatus();

  /** Create an {@link EppMetric.Builder}. */
  public static Builder builder() {
    return new AutoValue_EppMetric.Builder();
  }

  /**
   * Create an {@link EppMetric.Builder} for a request context, with the given request ID and
   * with start and end timestamps taken from the given clock.
   *
   * <p>The start timestamp is recorded now, and the end timestamp at {@code build()}.
   */
  public static Builder builderForRequest(Clock clock) {
      return builder()
          .setStartTimestamp(clock.nowUtc())
          .setClock(clock);
  }

  /** A builder to create instances of {@link EppMetric}. */
  @AutoValue.Builder
  public abstract static class Builder {

    /** Builder-only clock to support automatic recording of endTimestamp on {@link #build()}. */
    private Clock clock = null;

    abstract Builder setStartTimestamp(DateTime startTimestamp);

    abstract Builder setEndTimestamp(DateTime endTimestamp);

    abstract Builder setCommandName(String commandName);

    public Builder setCommandNameFromFlow(String flowSimpleClassName) {
      checkArgument(
          flowSimpleClassName.endsWith("Flow"),
          "Must pass in the simple class name of a flow class");
      return setCommandName(flowSimpleClassName.replaceFirst("Flow$", ""));
    }

    public abstract Builder setRegistrarId(String registrarId);

    public abstract Builder setRegistrarId(Optional<String> registrarId);

    public abstract Builder setTld(String tld);

    public abstract Builder setTld(Optional<String> tld);

    /**
     * Sets the single TLD field from a list of TLDs associated with a command.
     *
     * <p>Due to cardinality reasons we cannot record combinations of different TLDs as might be
     * seen in a domain check command, so if this happens we record "_various" instead. We also
     * record "_invalid" for a TLD that does not exist in our system, as again that could blow up
     * cardinality. Underscore prefixes are used for these sentinel values so that they cannot be
     * confused with actual TLDs, which cannot start with underscores.
     */
    public Builder setTlds(ImmutableSet<String> tlds) {
      switch (tlds.size()) {
        case 0:
          setTld(Optional.empty());
          break;
        case 1:
          String tld = Iterables.getOnlyElement(tlds);
          // Only record TLDs that actually exist, otherwise we can blow up cardinality by recording
          // an arbitrarily large number of strings.
          setTld(Optional.ofNullable(Registries.getTlds().contains(tld) ? tld : "_invalid"));
          break;
        default:
          setTld("_various");
          break;
      }
      return this;
    }

    public abstract Builder setStatus(Code code);

    Builder setClock(Clock clock) {
      this.clock = clock;
      return this;
    }

    /**
     * Build an instance of {@link EppMetric} using this builder.
     *
     * <p>If a clock was provided with {@code setClock()}, the end timestamp will be set to the
     * current timestamp of the clock; otherwise end timestamp must have been previously set.
     */
    public EppMetric build() {
      if (clock != null) {
        setEndTimestamp(clock.nowUtc());
      }
      return autoBuild();
    }

    abstract EppMetric autoBuild();
  }
}
