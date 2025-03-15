// Copyright 2018 The Nomulus Authors. All Rights Reserved.
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

package google.registry.reporting.spec11;

import static java.lang.annotation.RetentionPolicy.RUNTIME;

import dagger.Module;
import dagger.Provides;
import google.registry.beam.spec11.Spec11Pipeline;
import jakarta.inject.Qualifier;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import org.joda.time.LocalDate;

/** Module for dependencies required by Spec11 reporting. */
@Module
public class Spec11Module {

  @Provides
  @Spec11ReportFilePath
  static String provideSpec11ReportFilePath(LocalDate localDate) {
    return Spec11Pipeline.getSpec11ReportFilePath(localDate);
  }

  /** Dagger qualifier for the subdirectory we stage to/upload from for Spec11 reports. */
  @Qualifier
  @Documented
  @Retention(RUNTIME)
  @interface Spec11ReportFilePath {}
}
