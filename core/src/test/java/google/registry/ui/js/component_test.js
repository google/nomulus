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

goog.setTestOnly();

goog.require('goog.dispose');
goog.require('goog.testing.MockControl');
goog.require('registry.Component');
goog.require('registry.Console');

describe("component test", function() {
  let mocks;

  beforeEach(function() {
    mocks = new goog.testing.MockControl();
  });

  afterEach(function() {
    mocks.$tearDown();
  });

  it("testCreationAndDisposal_dontTouchConsoleObject", function() {
    var console = mocks.createStrictMock(registry.Console);
    mocks.$replayAll();
    var component = new registry.Component(console);
    goog.dispose(component);
    mocks.$verifyAll();
  });
});
