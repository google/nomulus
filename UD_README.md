# Deployment notes - nomulus-pre-alpha2 environment
 Note: these steps are confirmed to work with the existing nomulus-pre-alpha2 environment.  The initial DB create/migrations/run of flyway migrations, any oauth config, or other steps not documented here (or in this PR) are hopefully captured somewhere else (or ask me)

 You can pass `--info` to a nom_build command to get more info. You will get pretty verbose output.

From the repository root directory:

 1. update nomulus-config-alpha.yaml with teh oauth client id and secret. you'll have to ask for this.
 1. `./nom_build build`
    ```
    For more on this, please refer to https://docs.gradle.org/8.13/userguide/command_line_interface.html#sec:command_line_warnings in the Gradle documentation.

    BUILD SUCCESSFUL in 4m 2s
    133 actionable tasks: 133 executed
    ```

 1. `./nom_build :console-webapp:build --environment alpha`
    ```
    TOTAL: 20 SUCCESS
    ERROR: 'Backend returned code 404, body was: NOT FOUND'
    Chrome Headless 136.0.0.0 (Mac OS 10.15.7): Executed 20 of 20 SUCCESS (0.524 secs / 0.38 secs)
    ERROR: 'Backend returned code 404, body was: NOT FOUND'
    Chrome Headless 136.0.0.0 (Mac OS 10.15.7) ERROR
    Disconnected , because no message in 30000 ms.
    Chrome Headless 136.0.0.0 (Mac OS 10.15.7): Executed 20 of 20 DISCONNECTED (30.53 secs / 0.38 secs)
    ✔ Browser application bundle generation complete.
    Chrome Headless 136.0.0.0 (Mac OS 10.15.7) ERROR
    Chrome Headless 136.0.0.0 (Mac OS 10.15.7): Executed 20 of 20 DISCONNECTED (30.53 secs / 0.38 secs)

    [Incubating] Problems report is available at: file:///Users/tjones/UDtorrey/nomulus/build/reports/problems/problems-report.html

    Deprecated Gradle features were used in this build, making it incompatible with Gradle 9.0.

    You can use '--warning-mode all' to show the individual deprecation warnings and determine if they come from your own scripts or plugins.

    For more on this, please refer to https://docs.gradle.org/8.13/userguide/command_line_interface.html#sec:command_line_warnings in the Gradle documentation.

    BUILD SUCCESSFUL in 49s
    5 actionable tasks: 4 executed, 1 up-to-date
    ```
    note: idk why some say `ERROR` but still succeed.

 1. `./nom_build appengineDeploy --environment=alpha`
    ```
    ((HEAVILY TRUNCATED))
    > Task :services:bsa:appengineDeploy
    > Task :services:backend:appengineDeploy
    > Task :services:pubapi:appengineDeploy
    > Task :services:default:appengineDeploy
    > Task :services:backend:appengineDeploy
    > Task :services:pubapi:appengineDeploy
    > Task :services:tools:appengineDeploy
    > Task :services:default:appengineDeploy
    > Task :services:tools:appengineDeploy

    BUILD SUCCESSFUL in 1m 48s
    42 actionable tasks: 22 executed, 20 up-to-date
    ```

1. `nvm use 20.5.1` then `./nom_build :console-webapp:buildConsoleForAlpha`
    ```
    > ng build --base-href=/console/ --configuration=$npm_config_configuration

    ❯ Building...
    ✔ Building...
    Initial chunk files             | Names               |  Raw size
    chunk-OVCP647X.js               | -                   |   4.01 MB |
    chunk-RDS7UYFR.js               | -                   | 427.20 kB |
    polyfills.js                    | polyfills           |  90.58 kB |
    styles.css                      | styles              |  61.80 kB |
    main.js                         | main                | 346 bytes |

                                    | Initial total       |   4.59 MB

    Lazy chunk files                | Names               |  Raw size
    users.component-SXQHP6OS.js     | users-component     |  40.95 kB |
    oteStatus.component-DJHE4TJU.js | oteStatus-component |   8.12 kB |
    newOte.component-HRH7KR5Q.js    | newOte-component    |   6.64 kB |

    Application bundle generation complete. [6.575 seconds]

    Output location: /Users/tjones/UDtorrey/nomulus/console-webapp/staged/dist


    [Incubating] Problems report is available at: file:///Users/tjones/UDtorrey/nomulus/build/reports/problems/problems-report.html

    Deprecated Gradle features were used in this build, making it incompatible with Gradle 9.0.

    You can use '--warning-mode all' to show the individual deprecation warnings and determine if they come from your own scripts or plugins.

    For more on this, please refer to https://docs.gradle.org/8.13/userguide/command_line_interface.html#sec:command_line_warnings in the Gradle documentation.

    BUILD SUCCESSFUL in 9s
    2 actionable tasks: 2 executed
    <-------------> 0% WAITING
    > IDLE
    ```
1. `nom_build :console-webapp::appengineDeploy --environment=alpha`
    I HAVE PROBLEMS WITH THIS
    ```
    output here
    ```

1. if needed edit `core/src/main/java/google/registry/env/alpha/bsa/WEB-INF/appengine-web.xml` for the vpc-access-connector setting, then `./nom_build :services:bsa:build --environment=alpha` and `./nom_build :services:bsa:appengineDeploy --environment=alpha`

 # How to run nomulus CLI tool locally
 From the repository root directory:
 1. ask to get your user added to the oauth client test user credential list
 1. `nom_build buildToolImage`

    note: your nomulus-config-*.yaml gets baked into the jar that is produced! so any changes to your config needs to have a rebuild

 1. `alias nomulus='java -jar  /Users/tjones/UDtorrey/nomulus/core/build/libs/nomulus.jar'`
 1. `nomulus -e alpha login`
 1. `nomulus -e alpha  --canary --gae list_tlds`


 Notes

* make sure you have been granted access (audience tab) to the oauth client in the GCP console
* a nomulus user also needs created (and optionally provided admin rights)
* depending on the GAE service it communicates with, sometimes you have to use `--canary --use_gae`

### creation of nomulus user
1.  `nomulus -e alpha create_user --email aaron.quirk@unstoppabledomains.com --global_role FTE`
1. (Optional for admin access) `nomulus -e alpha update_user --email aaron.quirk@unstoppabledomains.com --admin true`

```
➜  nomulus git:(torrey/may6-nomulus-pre-alpha2) ✗ nomulus -e alpha create_user --email aaron.quirk@unstoppabledomains.com --global_role FTE
Perform this command? (y/N): y
Running ...
May 09, 2025 6:28:31 AM google.registry.model.console.User grantIapPermission
INFO: Granting IAP role to user aaron.quirk@unstoppabledomains.com
Saved user with email aaron.quirk@unstoppabledomains.com
➜  nomulus git:(torrey/may6-nomulus-pre-alpha2) ✗ nomulus -e alpha get_user aaron.quirk@unstoppabledomains.com
User: {
    emailAddress=aaron.quirk@unstoppabledomains.com
    registryLockEmailAddress=null
    registryLockPasswordHash=null
    registryLockPasswordSalt=null
    updateTimestamp=UpdateAutoTimestamp: {
        lastUpdateTime=2025-05-09T12:28:30.498Z
    }
    userRoles=UserRoles: {
        globalRole=FTE
        isAdmin=false
        registrarRoles={}
    }
}
➜  nomulus git:(torrey/may6-nomulus-pre-alpha2) ✗ nomulus -e alpha update_user --email aaron.quirk@unstoppabledomains.com --admin true
Perform this command? (y/N): y
Running ...
Saved user with email aaron.quirk@unstoppabledomains.com
➜  nomulus git:(torrey/may6-nomulus-pre-alpha2) ✗ nomulus -e alpha get_user aaron.quirk@unstoppabledomains.com
User: {
    emailAddress=aaron.quirk@unstoppabledomains.com
    registryLockEmailAddress=null
    registryLockPasswordHash=null
    registryLockPasswordSalt=null
    updateTimestamp=UpdateAutoTimestamp: {
        lastUpdateTime=2025-05-09T12:30:19.679Z
    }
    userRoles=UserRoles: {
        globalRole=FTE
        isAdmin=true
        registrarRoles={}
    }
}
```

 

 ## Quick demo/howto

 # Create premium list
`nomulus -e alpha  create_premium_list -n demo2 -i core/src/main/java/google/registry/config/files/premium/demo2.txt -c USD -o`


`nomulus -e alpha  --canary --gae list_premium_lists # list premium lists`
```
nomulus -e alpha  --canary --gae list_premium_lists
demo1
demo2
torrey
```

# Create/Configure TLD
`nomulus -e alpha configure_tld  -i core/src/test/resources/google/registry/tools/demo2.yaml `

`nomulus -e alpha  --canary --gae list_tlds``

# Create Registrar
`nomulus -e alpha create_registrar demo2 --name 'demo2 Registrar' --registrar_type TEST --password ud_pre_alpha --icann_referral_email torrey+demo2+registrar@unstoppabledomains --street '123 fake St' --city 'Las Veges' --state NV --zip 1234 --cc US
nomulus -e alpha  --canary --gae list_registrars # list registrars`