#!/bin/bash

WORKING_DIR=`cd $(dirname $0); pwd`
SRC=${WORKING_DIR}/src
DIST=${WORKING_DIR}/dist

for maven in Infinispan Radargun; do
cd ${SRC}/${maven}
mvn clean install -DskipTests;
cd -
done

cd ${SRC}/Csv-reporter
ant dist
cd -

mkdir -p ${DIST} 2>/dev/null

cp -r ${SRC}/Radargun/target/distribution/Radargun-1.1.1-SNAPSHOT/* ${DIST}/
cp ${SRC}/Csv-reporter/dist/lib/WpmCsvReporter.jar  ${DIST}/lib/
cp ${SRC}/Csv-reporter/config.properties ${DIST}/conf

mkdir -p ${DIST}/ml
cp ${SRC}/Machine-learner/* ${DIST}/ml/

echo "done!"

exit 0;

