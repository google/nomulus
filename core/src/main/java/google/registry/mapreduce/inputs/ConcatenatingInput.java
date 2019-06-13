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
import com.google.appengine.tools.mapreduce.inputs.ConcatenatingInputReader;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ListMultimap;
import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * A MapReduce {@link Input} adapter that joins multiple inputs.
 *
 * @param <T> input type
 */
public class ConcatenatingInput<T> extends Input<T> {

  private static final long serialVersionUID = 1225981408139437077L;

  private final Set<? extends Input<? extends T>> inputs;
  private final int numShards;

  public ConcatenatingInput(Iterable<? extends Input<? extends T>> inputs, int numShards) {
    this.inputs = ImmutableSet.copyOf(inputs);
    this.numShards = numShards;
  }

  @Override
  public List<InputReader<T>> createReaders() throws IOException {
    ListMultimap<Integer, InputReader<T>> shards = ArrayListMultimap.create();
    int i = 0;
    for (Input<? extends T> input : inputs) {
      for (InputReader<? extends T> reader : input.createReaders()) {
        // Covariant cast is safe because an InputReader<I> only outputs I and never consumes it.
        @SuppressWarnings("unchecked")
        InputReader<T> typedReader = (InputReader<T>) reader;
        shards.put(i % numShards, typedReader);
        i++;
      }
    }
    ImmutableList.Builder<InputReader<T>> concatenatingReaders = new ImmutableList.Builder<>();
    for (Collection<InputReader<T>> shard : shards.asMap().values()) {
      concatenatingReaders.add(new ConcatenatingInputReader<>(ImmutableList.copyOf(shard)));
    }
    return concatenatingReaders.build();
  }
}
