## EPP Load Testing Client

This project contains an EPP client that can be use for load testing the full 
registry platform. All the below commands should be run from the merged root.

### Setting up the test instances

* If you have not done s yet, you will need to set up ssh keys in your 
[GCE metadata](https://pantheon.corp.google.com/compute/metadata?resourceTab=sshkeys):

* To create however many GCE instances you want to run on, modify the 
`instanceSetUp.sh` file to include the correct number of instances. 

* Run the instance set up script to create and configure each of the GCE 
instances to be used for load testing:

  ```shell
  $ load-testing/instanceSetUp.sh
  ```

* Verify that the IP address of any created instances is in the allowlist of the
  proxy registrar user.
    * Use the below command to get the IP addresses of the created instances.

        ```shell
        $ (gcloud compute instances list | awk '/^loadtest/ { print $5 }')
        ```

    * Check the proxy registrar's current allow list

      ```shell
      $ nomulus -e sandbox get_registrar proxy | grep ipAddressAllowList
      ```

    * All of your host ip addresses should match a netmask specified in the proxy
      allow list. If not, you'll need to redefine the list:

      ```shell
      $ nomulus -e sandbox update_registrar proxy --ip_allow_list=<new-comma-separated-allowlist>
      ```


### Running the client

* From the merged root build the load testing client:
  ```shell
  $ ./nom_build :load-testing:buildLoadTestClient
    ```

* Deploy the client to the GCE instances (this will create a local staging 
directory and deploy it to each of your previously created loadtest GCE instances): 
  ```shell
  $ ./nom_build :load-testing:deployLoadTestsToInstances
    ```

* Run the load test. Configurations of the load test can be made by configuring 
this `run.sh` file locally.

    ```shell
    $ load-testing/run.sh
    ```

### Cleanup

* Run the instance clean up script to delete the created instances

    ```shell
    $ load-testing/instanceCleanUp.sh
    ```
  
* You may want to remove any host key fingerprints for those hosts from your ~/.ssh/known_hosts file (these IPs tend to get reused with new host keys)


