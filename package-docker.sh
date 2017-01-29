#!/bin/bash
activator clean compile
activator docker:stage
STAGING_PATH=target/docker/stage
echo "EXPOSE 9000" >> $STAGING_PATH/Dockerfile
cp Dockerrun.aws.json $STAGING_PATH/
cd $STAGING_PATH
zip -r docker.zip *

