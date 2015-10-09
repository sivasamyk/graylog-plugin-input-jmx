# Graylog JMX Input Plugin

Graylog input plugin to monitor JMX end points with built-in support for JVM and Tomcat endpoints

Features
--------

* No agent required in the machines to be monitored
* Support for Authentication
* Built-in support for monitoring JVM and Tomcat servers (more to come)
* Monitor multiple servers from single input plugin instance
* Support for monitoring custom JMX endpoints

Setup
-----

Download the plugin jar and copy to graylog plugin directory (restart the graylog server for the changes to take effect).
From Graylog UI, launch System->Input and select "JMX" input type

Following parameters can be configured

* Servers to monitor - Comma separated value of list of server IP Address or names to be monitored e.g. (10.220.5.123,webserver )
* Port - Port on which the JMX endpoint is listening ( firewall should be configured for bidirectional access to this port)
* JMX Object type - List of built-in JMX Object Types available. Select 'Custom' for monitoring custom endpoints. 
In this case the json config file path has to be specified in 'Config File Path' parameter
* Username - Username configured in JMX access file (applicable  when JMX authentication is enabled)
* Password - Password configured in JMX password file (applicable  when JMX authentication is enabled)
* Polling Interval - Interval to poll JMX endpoints (recommend to set the interval > 30 secs)
* Polling Interval time unit - Polling interval time unit

Custom Configuration
--------------------

To monitor custom JMX object types ( and to extend existing JMX type), a custom config file can be writtern and 
specified while launching the plugin. Example config file
 
 ```
 {
   "type": "jvm", /* Type of the endpoint */
   "queries": [
     {
       "object": "java.lang:type=Memory", /* JMX ObjectName */
       "attributes": [
         {
           "name": "HeapMemoryUsage", /* JMX MBean Attribute Name */
           "key": "used", /* JMX Attribute Key if applicable */
           "label": "jvm.mem.heap.used" /* Label for this attribute. Maps to a field in the graylog message. Allowed characters are A-Z,a-z,0-9,.,_ */ 
         }
       ]
     },
     {
       "object": "java.lang:type=GarbageCollector,name=*",
       "attributes": [
         {
           "name": "CollectionCount",
           "label": "jvm.gc.{name}.count" /* Support for dynamic field names based on object name property values */
         }
       ]
     }
   ]
 }
 ```

This plugin uses the JMX Query code from [JMXTrans](https://github.com/jmxtrans/jmxtrans) project


