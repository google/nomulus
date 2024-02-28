#!/bin/sh

environment=$1
cd /jetty-base
java -jar /usr/local/jetty/start.jar -Dgoogle.registry.environment=${environment:-"alpha"}
