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

package google.registry.util;

import com.google.common.base.Predicate;
import com.google.common.collect.FluentIterable;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Logging wrapper. */
public class FormattingLogger {

  private final Logger logger;

  /** Returns an instance using the caller's class name for the underlying logger's name. */
  public static FormattingLogger getLoggerForCallerClass() {
    return new FormattingLogger(new Exception().getStackTrace()[1].getClassName());
  }

  private FormattingLogger(String name) {
    this.logger = Logger.getLogger(name);
  }

  private void log(Level level, Throwable cause, String msg) {
    StackTraceElement callerFrame = FluentIterable
        .from(new Exception().getStackTrace())
        .firstMatch(new Predicate<StackTraceElement>() {
            @Override
            public boolean apply(StackTraceElement frame) {
              return !frame.getClassName().equals(FormattingLogger.class.getName());
            }}).get();
    if (cause == null) {
      logger.logp(level, callerFrame.getClassName(), callerFrame.getMethodName(), msg);
    } else {
      logger.logp(level, callerFrame.getClassName(), callerFrame.getMethodName(), msg, cause);
    }
  }

  public void finefmt(String fmt, Object... args) {
    log(Level.FINE, null, String.format(fmt, args));
  }

  public void info(String msg) {
    log(Level.INFO, null, msg);
  }

  public void info(Throwable cause, String msg) {
    log(Level.INFO, cause, msg);
  }

  public void infofmt(String fmt, Object... args) {
    log(Level.INFO, null, String.format(fmt, args));
  }

  public void infofmt(Throwable cause, String fmt, Object... args) {
    log(Level.INFO, cause, String.format(fmt, args));
  }

  public void warning(String msg) {
    log(Level.WARNING, null, msg);
  }

  public void warning(Throwable cause, String msg) {
    log(Level.WARNING, cause, msg);
  }

  public void warningfmt(String fmt, Object... args) {
    log(Level.WARNING, null, String.format(fmt, args));
  }

  public void warningfmt(Throwable cause, String fmt, Object... args) {
    log(Level.WARNING, cause, String.format(fmt, args));
  }

  public void severe(String msg) {
    log(Level.SEVERE, null, msg);
  }

  public void severe(Throwable cause, String msg) {
    log(Level.SEVERE, cause, msg);
  }

  public void severefmt(String fmt, Object... args) {
    log(Level.SEVERE, null, String.format(fmt, args));
  }

  public void severefmt(Throwable cause, String fmt, Object... args) {
    log(Level.SEVERE, cause, String.format(fmt, args));
  }

  public void addHandler(Handler handler) {
    logger.addHandler(handler);
  }

  public void removeHandler(Handler handler) {
    logger.removeHandler(handler);
  }
}
