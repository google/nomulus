# Registry Console Swagger API 

This is the Swagger-based Registry Console API documentation. The project endpoints documentation in json format can be found in `console-api-swagger.json`, rest of the files in the folder compose Swagger distribution stripped to bare miminum necessary to start the Swagger UI. 

## How to run

The following steps are required to start Swagger ui:

* Install npm dependencies - `npm install`
* Run - `npm run swagger`
* Make changes in `console-api-swagger.json`
    * Upon making changes make sure your browser cache is turned off


## How to update the Swagger UI

In order to update Swagger version the following steps should to be taken:

* Download Swagger distributive 
* Remove `.map.*` files as they are only needed to debug Swagger UI and not to run it
* Add the link to Console API Swagger documentation file - `console-api-swagger.json` to the `swagger-initializer.js`
* Copy with replace into the current directory


