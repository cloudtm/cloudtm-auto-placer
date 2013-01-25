#!/bin/bash

WORKING_DIR=`cd $(dirname $0); pwd`
SRC=${WORKING_DIR}/src
DIST=${WORKING_DIR}/dist

for maven in Radargun Infinispan; do
cd ${SRC}/${maven}
mvn clean;
cd -
done

cd ${SRC}/Csv-reporter
ant clean
cd -

rm -r ${DIST}/* 2>/dev/null

echo "done!"

exit 0;
