# Local Testing

## Running a local development server

Nomulus provides a `RegistryTestServer` that is a lightweight test server
suitable for running local development. It uses local versions of all Google
Cloud Platform dependencies when available. Correspondingly, it is primarily
useful for doing web UI development (i.e. the registrar console). It allows you
to update Typescript, HTML, and CSS and see the changes simply by refreshing the
relevant page in your browser.

In order to serve content locally, there are two services that must be run: *
the `RegistryTestServer` to serve as the backing server * the Angular service to
provide the UI files

In order to do this in one step, from the `console-webapp` folder, run:

```shell
$ npm install
$ npm run start:dev
```

This will start both the `RegistryTestServer` and the Angular testing service.
Any changes to Typescript/HTML/CSS files will be recompiled and available on
page reload.

One it is running, you can interact with the console by going to
`http://localhost:4200` to view the registrar console in a web browser. The
server will continue running until you terminate the process.

If you are adding new URL paths, or new directories of web-accessible resources,
you will need to make the corresponding changes in `RegistryTestServer`. This
class contains all the routing and static file information used by the local
development server.
