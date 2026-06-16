# ConsoleWebapp

A web application for managing [Nomulus](https://github.com/google/nomulus).

## Status

Console webapp is currently under active development and some parts of it are
expected to change.

## Deployment

The webapp is deployed as part of the default Nomulus GKE service image.
During the image build task, the Gradle script triggers the following:

1) Console webapp build script `buildConsoleWebapp`, which installs
   dependencies, assembles a compiled ts -> js, minified, optimized static
   artifact (html, css, js)
2) Artifact assembled in step 1 then gets copied to the jetty webapp resource
   location, so that it can be staged inside the default GKE service container.

## Development server

Run `npm run start:dev` to start both webapp dev server and API server instance.
Navigate to `http://localhost:4200/`. The application will automatically reload
if you change any of the source files.

## Code scaffolding

Run `ng generate component component-name` to generate a new component. You can
also use `ng generate directive|pipe|service|class|guard|interface|enum|module`.

## Build

Run `ng build` to build the project. The build artifacts will be stored in
the `dist/` directory.

## Running unit tests

Run `ng test` to execute the unit tests
via [Karma](https://karma-runner.github.io).

## Running end-to-end tests

Run `ng e2e` to execute the end-to-end tests via a platform of your choice. To
use this command, you need to first add a package that implements end-to-end
testing capabilities.

## Further help

To get more help on the Angular CLI use `ng help` or go check out
the [Angular CLI Overview and Command Reference](https://angular.io/cli) page.
