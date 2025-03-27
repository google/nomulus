#!/bin/sh
# Copyright 2024 The Nomulus Authors. All Rights Reserved.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

env=${1:-"alpha"}
cd /jetty-base
mkdir -p webapps/console-${env}/WEB-INF
cp -rf webapps/console-${env} webapps/console
cd webapps

# Remove all environment builds not used in the deployment
find . -maxdepth 1 -type d -name "console-*" -exec rm -rf {} +

# Configure cache policies for Registry Console static resources
echo '<web-app xmlns="https://jakarta.ee/xml/ns/jakartaee"
               xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
               xsi:schemaLocation="https://jakarta.ee/xml/ns/jakartaee https://jakarta.ee/xml/ns/jakartaee/web-app_5_0.xsd"
               version="5.0">

            <servlet>
                <servlet-name>default-no-cache</servlet-name>
                <servlet-class>org.eclipse.jetty.ee10.servlet.DefaultServlet</servlet-class>
                <init-param>
                    <param-name>cacheControl</param-name>
                    <param-value>no-cache, no-store, must-revalidate</param-value>
                </init-param>
            </servlet>

            <servlet>
                <servlet-name>default-cache-static</servlet-name>
                <servlet-class>org.eclipse.jetty.ee10.servlet.DefaultServlet</servlet-class>
                <init-param>
                    <param-name>cacheControl</param-name>
                    <param-value>public, max-age=31536000</param-value>
                </init-param>
            </servlet>

            <servlet-mapping>
                <servlet-name>default-no-cache</servlet-name>
                <url-pattern>*.html</url-pattern>
            </servlet-mapping>

            <servlet-mapping>
                <servlet-name>default-no-cache</servlet-name>
                <url-pattern>main.js</url-pattern>
            </servlet-mapping>

            <servlet-mapping>
                <servlet-name>default-no-cache</servlet-name>
                <url-pattern>styles.css</url-pattern>
            </servlet-mapping>

            <servlet-mapping>
                <servlet-name>default-cache-static</servlet-name>
                <url-pattern>*.css</url-pattern>
            </servlet-mapping>

            <servlet-mapping>
                <servlet-name>default-cache-static</servlet-name>
                <url-pattern>*.js</url-pattern>
            </servlet-mapping>

            <servlet-mapping>
                <servlet-name>default-cache-static</servlet-name>
                <url-pattern>*.png</url-pattern>
            </servlet-mapping>

    </web-app>' > console/WEB-INF/web.xml

cd /jetty-base
echo "Running ${env}"
java -Dgoogle.registry.environment=${env} \
    -Djava.util.logging.config.file=/logging.properties \
    -jar /usr/local/jetty/start.jar
