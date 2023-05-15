# Registry Console Swagger API 

This is the Swagger-based Registry Console API documentation. The project endpoints documentation in json format can be found in `console-api-swagger.json`, rest of the files in the folder compose Swagger standalone distributive stripped to bare miminum necessary to start the Swagger UI. 

## How to run swagger to view the endpoints documentaion

Since Swagger standalone distributive is nothing but a simple static website it's extremely easy to get it started and view the documentation in a matter of seconds. 
Note - just a simple opening of `index.html` wouldn't be enough, as with any static website, it'd not be able to handle relative resources requests therefore succesfully render Swagger UI. For this reason it's configured to spin off a simple node.js based http server to serve Swagger UI resources. The following steps required to succesfully start the Swagger UI:

* Install npm dependencies - `npm install`
* Run - `npm run swagger`
* Make changes in `console-api-swagger.json`
    * Upon making changes make sure your browser cache is turned off


## How to update the Swagger UI

In order to update Swagger version the following steps should to be taken:

* [Download](https://swagger.io/docs/open-source-tools/swagger-ui/usage/installation/) Swagger standalone distributive
* Remove `.map.*` files as they are only needed to debug Swagger UI and not to run it
* Add the link to Console API Swagger documentation file - `console-api-swagger.json` to the `swagger-initializer.js`
* Copy with replace into the current directory


