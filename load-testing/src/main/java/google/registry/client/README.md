## EPP Load Testing Client

This project contains an EPP client that can be use for load testing the full 
registry platform.

### Setting up the test instances

*   Create however many GCE instances you want to run on (2 in the example
    below):

  ```shell
  $ gcloud compute instances create loadtest-{1..2} --machine-type g1-small --zone us-east4-a
  ```

* Verify that the IP address of any created instances is in the allowlist of the
  proxy.
    * Use the below command to get the IP addresses of the created instances.

        ```shell
        $ (gcloud compute instances list | awk '/^loadtest/ { print $5 }')
        ```

    * Check the proxy's current allow list

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
  $ ./nom_build :load-testing:deployLoadTest
    ```

* Run the load test. Configurations of the load test can be made by configuring 
this `run.sh` file locally and re-deploying.

    ```shell
    $ HOSTS=$(gcloud compute instances list | awk '/^loadtest/ { print $5 }')
    $ for host in $HOSTS; do ssh $host test-client/run.sh; done
    ```

### Cleanup

* Delete the GCE instances

    ```shell
    $ gcloud compute instances delete loadtest-{1..2} --zone us-east4-a
    ```
  
* You may want to remove any host key fingerprints for those hosts from your ~/.ssh/known_hosts file (these IPs tend to get reused with new host keys)


