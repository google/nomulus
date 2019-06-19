process.env.CHROME_BIN = require('puppeteer').executablePath()

module.exports = function(config) {
  config.set({
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
      'core/build/resources/test/**/*_test.js',
      {
        pattern: 'core/build/resources/test/**/!(*_test).js',
        included: false
      },
      {
        pattern: 'core/build/resources/main/**/*.js',
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
      'core/build/resources/test/**/*_test.js': ['closure'],
      'core/build/resources/test/**/!(*_test).js': ['closure'],
      'core/build/resources/main/**/*.js': ['closure'],
      'core/build/generated/source/custom/main/**/*.soy.js': ['closure'],
      'node_modules/soyutils_usegoog.js': ['closure']
    },
    proxies: {
      "/assets/": "/base/core/build/resources/main/google/registry/ui/assets/"
    }
  });
};

