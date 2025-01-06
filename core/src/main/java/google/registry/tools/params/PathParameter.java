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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import com.beust.jcommander.ParameterException;
import com.google.re2j.Pattern;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Filesystem path CLI parameter converter/validator. */
public class PathParameter extends ParameterConverterValidator<Path> {

  public PathParameter() {
    super("not a valid path");
  }

  /** Heuristic for determining if a path value appears to be a {@link URI}. */
  private static final Pattern URI_PATTERN = Pattern.compile("[a-z][-+.a-z]+:[^:]");

  @Override
  public final Path convert(String value) {
    checkArgument(!checkNotNull(value).isEmpty());
    if (URI_PATTERN.matcher(value).lookingAt()) {
      return Paths.get(URI.create(value)).normalize();
    }
    return Paths.get(value).normalize();
  }

  /** {@linkplain PathParameter} when you want an output file parameter. */
  public static final class OutputFile extends PathParameter {
    @Override
    public void validate(String name, String value) throws ParameterException {
      super.validate(name, value);
      Path file = convert(value).toAbsolutePath();
      if (Files.exists(file)) {
        if (Files.isDirectory(file)) {
          throw new ParameterException(String.format("%s is a directory: %s", name, file));
        }
        if (!Files.isWritable(file)) {
          throw new ParameterException(String.format("%s not writable: %s", name, file));
        }
      } else {
        Path dir = file.getParent();
        if (!Files.exists(dir)) {
          throw new ParameterException(String.format("%s parent dir doesn't exist: %s", name, dir));
        }
        if (!Files.isDirectory(dir)) {
          throw new ParameterException(String.format("%s parent is non-directory: %s", name, dir));
        }
      }
    }
  }

  /** {@linkplain PathParameter} when you want an output directory parameter. */
  public static final class OutputDirectory extends PathParameter {
    @Override
    public void validate(String name, String value) throws ParameterException {
      super.validate(name, value);
      Path file = convert(value).toAbsolutePath();
      if (!Files.isDirectory(file)) {
        throw new ParameterException(String.format("%s not a directory: %s", name, file));
      }
    }
  }

  /** {@linkplain PathParameter} when you want an input file that must exist. */
  public static final class InputFile extends PathParameter {
    @Override
    public void validate(String name, String value) throws ParameterException {
      super.validate(name, value);
      Path file = convert(value).toAbsolutePath();
      if (!Files.exists(file)) {
        throw new ParameterException(String.format("%s not found: %s", name, file));
      }
      if (!Files.isReadable(file)) {
        throw new ParameterException(String.format("%s not readable: %s", name, file));
      }
      if (Files.isDirectory(file)) {
        throw new ParameterException(String.format("%s is a directory: %s", name, file));
      }
    }
  }
}
