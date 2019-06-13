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

package google.registry.mapreduce.inputs;

import com.google.appengine.tools.mapreduce.Input;
import com.google.appengine.tools.mapreduce.InputReader;
import com.google.common.collect.ImmutableList;
import java.util.List;
import java.util.NoSuchElementException;

/** An input that returns a single {@code null} value. */
public class NullInput<T> extends Input<T> {

  private static final long serialVersionUID = 1816836937031979851L;

  private static final class NullReader<T> extends InputReader<T> {

    private static final long serialVersionUID = -8176201363578913125L;

    boolean read = false;

    @Override
    public T next() throws NoSuchElementException {
      if (read) {
        throw new NoSuchElementException();
      }
      read = true;
      return null;
    }

    @Override
    public Double getProgress() {
      return read ? 1.0 : 0.0;
    }
  }

  @Override
  public List<? extends InputReader<T>> createReaders() {
    return ImmutableList.of(new NullReader<T>());
  }
}
