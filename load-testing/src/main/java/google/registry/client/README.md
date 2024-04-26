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

* Download the Java 21 JDK [jdk-21_linux-x64_bin.tar.gz](https://www.oracle.com/java/technologies/downloads/#java21)

* Build the client: 
  ```shell
  $ ./nom_build :load-testing:build
    ```
* Unzip the created client jar files: 
* 
    ```shell
  $ unzip load-testing/build/distributions/load-testing.zip -d load-testing/build/distributions/
    ```

* Build the staging directory. You will need a locally-stored `certificate.pem` 
and `key.pem` file with your SSL certificate and private key. DO NOT submit 
these files to GitHub! 

    ```shell
    $ mkdir stage
    $ cp -r load-testing/build/distributions/load-testing/ stage/
    $ cp -r jdk-21_linux-x64_bin.tar.gz stage/
    $ cp load-testing/src/main/java/google/registry/client/run.sh stage/
    $ cp certificate.pem stage/
    $ cp key.pem stage/
    ```

* Deploy the staging directory to all instances

    ```shell
    $ HOSTS=$(gcloud compute instances list | awk '/^loadtest/ { print $5 }')
    $ for host in $HOSTS; do ssh $host sudo apt-get -y install rsync; done
    $ for host in $HOSTS; do rsync -avz stage/ $host:test-client/; done
    ```

* Run the load test. Configurations of the load test can be made by configuring 
this `run.sh` file.

    ```shell
    $ for host in $HOSTS; do ssh $host test-client/run.sh; done
    ```

### Cleanup

* Delete the GCE instances

    ```shell
    $ gcloud compute instances delete loadtest-{1..2} --zone us-east4-a
    ```
  
* You may want to remove any host key fingerprints for those hosts from your ~/.ssh/known_hosts file (these IPs tend to get reused with new host keys)


