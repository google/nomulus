// Copyright 2019 The Nomulus Authors. All Rights Reserved.
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

process.env.CHROME_BIN = require('puppeteer').executablePath()

module.exports = function(config) {
  config.set({
    basePath: '..',
    browsers: ['ChromeHeadlessNoSandbox'],
    customLaunchers: {
      ChromeHeadlessNoSandbox: {
        base: 'ChromeHeadless',
        flags: ['--no-sandbox']
      }
    },
    frameworks: ['jasmine', 'closure'],
    singleRun: true,
    autoWatch: false,
    files: [
      'node_modules/google-closure-library/closure/goog/base.js',
      'core/src/test/javascript/**/*_test.js',
      {
        pattern: 'core/src/test/javascript/**/!(*_test).js',
        included: false
      },
      {
        pattern: 'core/src/main/javascript/**/*.js',
        included: false
      },
      {
        pattern: 'core/build/generated/source/custom/main/**/*.soy.js',
        included: false
      },
      {
        pattern: 'node_modules/soyutils_usegoog.js',
        included: false
      },
      {
        pattern: 'node_modules/google-closure-library/closure/goog/deps.js',
        included: false,
        served: false
      },
      {
        pattern: 'node_modules/google-closure-library/closure/goog/**/*.js',
        included: false
      },
      {
        pattern: 'core/build/resources/main/google/registry/ui/assets/images/*.png',
        included: false
      },
      {
        pattern: 'core/build/resources/main/google/registry/ui/assets/images/icons/svg/*.svg',
        included: false
      }
    ],
    preprocessors: {
      'node_modules/google-closure-library/closure/goog/deps.js': ['closure', 'closure-deps'],
      'node_modules/google-closure-library/closure/goog/base.js': ['closure'],
      'node_modules/google-closure-library/closure/**/*.js': ['closure'],
      'core/src/*/javascript/**/*.js': ['closure'],
      'core/build/generated/source/custom/main/**/*.soy.js': ['closure'],
      'node_modules/soyutils_usegoog.js': ['closure']
    },
    proxies: {
      "/assets/": "/base/core/build/resources/main/google/registry/ui/assets/"
    }
  });
};

