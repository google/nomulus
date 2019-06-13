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

import com.google.appengine.api.datastore.Key;
import com.google.appengine.tools.mapreduce.Input;
import com.google.appengine.tools.mapreduce.InputReader;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.util.List;
import java.util.NoSuchElementException;

/** A MapReduce {@link Input} adapter that chunks an input of keys into sublists of keys. */
public class ChunkingKeyInput extends Input<List<Key>> {

  private static final long serialVersionUID = 1670202385246824694L;

  private final Input<Key> input;
  private final int chunkSize;

  public ChunkingKeyInput(Input<Key> input, int chunkSize) {
    this.input = input;
    this.chunkSize = chunkSize;
  }

  /**
   * An input reader that wraps around another input reader and returns its contents in chunks of
   * a given size.
   */
  private static class ChunkingKeyInputReader extends InputReader<List<Key>> {

    private static final long serialVersionUID = 53502324675703263L;

    private final InputReader<Key> reader;
    private final int chunkSize;

    ChunkingKeyInputReader(InputReader<Key> reader, int chunkSize) {
      this.reader = reader;
      this.chunkSize = chunkSize;
    }

    @Override
    public List<Key> next() throws IOException {
      ImmutableList.Builder<Key> chunk = new ImmutableList.Builder<>();
      try {
        for (int i = 0; i < chunkSize; i++) {
          chunk.add(reader.next());
        }
      } catch (NoSuchElementException e) {
        // Amazingly this is the recommended (and only) way to test for hasNext().
      }
      ImmutableList<Key> builtChunk = chunk.build();
      if (builtChunk.isEmpty()) {
        throw new NoSuchElementException();  // Maintain the contract.
      }
      return builtChunk;
    }

    @Override
    public Double getProgress() {
      return reader.getProgress();
    }

    @Override
    public void beginShard() throws IOException {
      reader.beginShard();
    }

    @Override
    public void beginSlice() throws IOException {
      reader.beginSlice();
    }

    @Override
    public void endSlice() throws IOException {
      reader.endSlice();
    }

    @Override
    public void endShard() throws IOException {
      reader.endShard();
    }

    @Override
    public long estimateMemoryRequirement() {
      // The reader's memory requirement plus the memory for this chunk's worth of buffered keys.
      // 256 comes from DatastoreKeyInputReader.AVERAGE_KEY_SIZE.
      return reader.estimateMemoryRequirement() + chunkSize * 256;
    }
  }

  @Override
  public List<InputReader<List<Key>>> createReaders() throws IOException {
    ImmutableList.Builder<InputReader<List<Key>>> readers = new ImmutableList.Builder<>();
    for (InputReader<Key> reader : input.createReaders()) {
      readers.add(new ChunkingKeyInputReader(reader, chunkSize));
    }
    return readers.build();
  }
}
