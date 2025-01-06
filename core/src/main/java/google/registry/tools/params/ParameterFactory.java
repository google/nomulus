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

package google.registry.tools.params;

import com.beust.jcommander.IStringConverter;
import com.beust.jcommander.IStringConverterFactory;
import com.google.common.collect.ImmutableMap;
import com.google.common.net.HostAndPort;
import com.google.common.net.InternetDomainName;
import java.nio.file.Path;
import java.util.logging.Level;
import javax.annotation.Nullable;
import org.joda.money.Money;
import org.joda.time.DateTime;
import org.joda.time.Duration;
import org.joda.time.Interval;
import org.joda.time.LocalDate;
import org.joda.time.YearMonth;

/** JCommander converter factory that works for non-internal converters. */
public final class ParameterFactory implements IStringConverterFactory {

  /** Returns JCommander converter for a given type, or {@code null} if none exists. */
  @Nullable
  @Override
  public Class<? extends IStringConverter<?>> getConverter(@Nullable Class<?> type) {
    return CONVERTERS.get(type);
  }

  private static final ImmutableMap<Class<?>, Class<? extends IStringConverter<?>>> CONVERTERS =
      new ImmutableMap.Builder<Class<?>, Class<? extends IStringConverter<?>>>()
          .put(DateTime.class, DateTimeParameter.class)
          .put(Duration.class, DurationParameter.class)
          .put(HostAndPort.class, HostAndPortParameter.class)
          .put(InternetDomainName.class, InternetDomainNameParameter.class)
          .put(Interval.class, IntervalParameter.class)
          .put(Level.class, LoggingLevelParameter.class)
          .put(LocalDate.class, LocalDateParameter.class)
          .put(Money.class, MoneyParameter.class)
          .put(Path.class, PathParameter.class)
          .put(YearMonth.class, YearMonthParameter.class)
          .build();


}
