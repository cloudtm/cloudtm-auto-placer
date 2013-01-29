#Cloud-TM Auto Placer

This package provides a demonstration of Auto Placer self-tunning. The requirement to run are:

* Maven (3.0.x)
* Java 1.6.x
* Apache Web Server with PHP (optional, only if you want to see the system performance in real time plots)

##How to run it

###Compilation

Execute the following commands to compile and create the distribution folder:

    # clone the repository by doing git clone <url>
    $ cd cloudtm-auto-placer
    $ export WORKING_DIR=`pwd`
    $ ./dist.sh
    

###Configuration: CSV Reporter (with Apache Web Server)

1.  copy the <code>www/</code> folder to you apache HTML folder

    (important!) make sure you have write permission in that folder

    <pre>
    $ #assuming your apache web server is getting the HMTL from /var/www
    $ export WEB=/var/www/example;
    $ mkdir ${WEB}
    $ cp -r ${WORKING_DIR}/www/* ${WEB}
    $ chmod -R +w ${WEB}
    </pre>
    
2.  configure the csv reporter by editing the file <code>dist/conf/config.properties</code>

    <b>reporter.ips</b> - add here the hostname and the JMX port (by default 9998) of the machines to use 
    separated by a comma.
    
    <b>reporter.output_file</b> - the output file. Assuming location in 1.set the property to 
    <code>/var/www/example/current/reporter.csv</code>
    
    <b>reporter.updateInterval</b> - the update interval in seconds. In each <b>updateInterval<b> seconds
    it adds a new line in the <b>output_file</b>
    
###Configuration: CSV Reporter (without Apache Web Server)
    
1.  configure the csv reporter by editing the file <code>dist/conf/config.properties</code>

    <b>reporter.ips</b> - add here the hostname and the JMX port (by default 9998) of the machines to use
    
    <b>reporter.output_file</b> - the output file path (for example <code>/tmp/reporter.csv</code>)
     
    <b>reporter.updateInterval</b> - the update interval in seconds. In each <b>updateInterval</b> seconds
    it adds a new line in the <b>output_file</b>

###Configuration: Radargun and Infinispan

1.  configure the master machine. This example we have a master process where each node synchronizes to run
    the example. Edit the file <code>dist/conf/benchmark.xml</code> and set the <b>bindAddress</b> with the 
    hostname or IP of the master.

2.  configure the Gossip Router. In order to the Infinispan instance to see each other, you need to configure
    the gossip router. The gossip router is started automatically in the master node. You need to set the
    hostname or IP in <code>dist/plugins/infinispan52cloudtm/conf/jgroups/jgroups.xml</code> and set the
    <b>initial_hosts</b> in TCPGOSSIP tag.

3.  if your machine does not support the C5.0 version provided (it is compiled for Linux) you can avoid using
    the machine learner by setting the property <b>objectLookupFactory</b> (dataPlacement tag in <code>
    dist/plugins/infinispan52cloudtm/conf/ispn.xml</code>) to <code>org.infinispan.dataplacement.hm.HashMapObjectLookupFactory</code>
    
###Running the benchmark

1.  copy the dist folder to all the machines to a common location

    <code> $ for machine in \<list of IP or hostnames\>; do scp -r ${WORKING_DIR}/dist/ \<username\>@${machine}:/tmp/; done</code>

2.  go to the master machine and execute the following

    <pre>
    $ ssh [username]>@[master machine IP or hostname]
    $ cd /tmp/dist/
    $ ./bin/benchmark.sh -i [number of nodes] [list of hostnames or IP addresses]
    </pre>

3.  If you have the apache web server installed, point your browser to <code>http://\<master machine IP or hostname\>/example</code>
    Otherwise, check the file defined in <b>reporter.output_file</b>