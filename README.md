cassandra-reaper
================

Cassandra Reaper is a centralized, stateful, and highly configurable tool for running Cassandra
repairs for multi-site clusters.

Current version supports running Cassandra cluster repairs in segmented manner, with
opportunistically running multiple parallel repairs at the same time on different nodes
within the cluster. Basic repair scheduling functionality is also supported.

Cassandra Reaper does not come with a GUI, but please check
[this project](https://github.com/spodkowinski/cassandra-reaper-ui) if you'd like to use one.

Please see the [Issues](https://github.com/spotify/cassandra-reaper/issues) section for more
information on planned development, and known issues.


System Overview
---------------

Cassandra Reaper consists of a database containing the full state of the system, a REST-full API,
and a CLI tool called *spreaper* that provides an alternative way to issue commands to a running
Reaper instance. Communication with Cassandra nodes in registered clusters is handled through JMX.

Reaper system does not use internal caches for state changes regarding running repairs and
registered clusters, which means that any changes done to the storage will reflect to the running
system dynamically.

You can also run the Reaper with memory storage, which is not persistent, and is meant to
be used only for testing purposes.

This project is built on top of Dropwizard:
http://dropwizard.io/


Usage
-----

To run Cassandra Reaper you need to simply build a project package using Maven, and
then execute the created Java jar file, and give a path to the system configuration file
as the first and only argument. You can also use the provided *bin/cassandra-reaper* script
to run the service.

When using database based storage, you must setup a PostgreSQL database yourself and configure
Reaper to use it. You need to prepare the database using the given schema in:
*src/main/db/reaper_db.sql*

For configuring the service, see the available configuration options in later section
of this readme document.

You can call the service directly through the REST API using a tool like *curl*. You can also
use the provided CLI tool in *bin/spreaper* to call the service.
Run the tool with *-h* or *--help* option to see usage instructions.

Notice that you can also build a Debian package from this project by using *debuild*, for example:
*debuild -uc -us -b*


Configuration
-------------

An example testing configuration YAML file can be found from within this project repository:
*src/test/resources/cassandra-reaper.yaml*

The configuration file structure is provided by Dropwizard, and help on configuring the server,
database connection, or logging, can be found from:
*http://dropwizard.io/manual/configuration.html*

The Reaper service specific configuration values are:

* segmentCount:

  Defines the default amount of repair segments to create for newly registered Cassandra
  repair runs (token rings). When running a repair run by the Reaper, each segment is
  repaired separately by the Reaper process, until all the segments in a token ring
  are repaired. The count might be slightly off the defined value, as clusters residing
  in multiple data centers require additional small token ranges in addition to the expected.
  You can overwrite this value per repair run, when calling the Reaper.

* repairParallelism:

  Defines the default type of parallelism to use for repairs.
  Repair parallelism value must be one of: "sequential", "parallel", or "datacenter_aware".
  If you try to use "datacenter_aware" in clusters that don't support it yet (older than 2.0.12),
  Reaper will fall back into using "sequential" for those clusters.

* repairIntensity:

  Repair intensity is a value between 0.0 and 1.0, but not zero. Repair intensity defines
  the amount of time to sleep between triggering each repair segment while running a repair run.
  When intensity is one, it means that Reaper doesn't sleep at all before triggering next segment,
  and otherwise the sleep time is defined by how much time it took to repair the last segment
  divided by the intensity value. 0.5 means half of the time is spent sleeping, and half running.
  Intensity 0.75 means that 25% of the total time is used sleeping and 75% running.
  This value can also be overwritten per repair run when invoking repairs.

* repairRunThreadCount:

  The amount of threads to use for handling the Reaper tasks. Have this big enough not to cause
  blocking in cause some thread is waiting for I/O, like calling a Cassandra cluster through JMX.

* hangingRepairTimeoutMins:

  The amount of time in minutes to wait for a single repair to finish. If this timeout is reached,
  the repair segment in question will be cancelled, if possible, and then scheduled for later
  repair again within the same repair run process.

* scheduleDaysBetween:

  Defines the amount of days to wait between scheduling new repairs.
  The value configured here is the default for new repair schedules, but you can also
  define it separately for each new schedule. Using value 0 for continuous repairs
  is also supported.

* storageType:

  Whether to use database or memory based storage for storing the system state.
  Value can be either "memory" or "database".
  If you are using the recommended (persistent) storage type "database", you need to define
  the database client parameters in a database section in the configuration file. See the example
  settings in provided testing configuration in *src/test/resources/cassandra-reaper.yaml*.

* jmxPorts:

  Optional mapping of custom JMX ports to use for individual hosts. The used default JMX port
  value is 7199. [CCM](https://github.com/pcmanus/ccm) users will find IP and port number
  to add in `~/.ccm/<cluster>/*/node.conf` or by running `ccm <node> show`.

* jmxAuth:

  Optional setting for giving username and password credentials for the used JMX connections
  in case you are using password based JMX authentication with your Cassandra clusters.

* enableCrossOrigin:

  Optional setting which you can set to be "true", if you wish to enable the CORS headers
  for running an external GUI application, like [this project](https://github.com/spodkowinski/cassandra-reaper-ui).

Notice that in the *server* section of the configuration, if you want to bind the service
to all interfaces, use value "0.0.0.0", or just leave the *bindHost* line away completely.
Using "*" as bind value won't work.


REST API
--------

Source code for all the REST resources can be found from package com.spotify.reaper.resources.

## Ping Resource

* GET     /ping
  * Expected query parameters: *None*
  * Simple ping resource that can be used to check whether the reaper is running.

## Cluster Resource

* GET     /cluster
  * Expected query parameters:
      * *seedHost*: Limit the returned cluster list based on the given seed host. (Optional)
  * Returns a list of registered cluster names in the service.

* GET     /cluster/{cluster_name}
  * Expected query parameters:
    * *limit*: Limit the number of repair runs returned. Recent runs are prioritized. (Optional)
  * Returns a cluster object identified by the given "cluster_name" path parameter.

* POST    /cluster
  * Expected query parameters:
      * *seedHost*: Host name or IP address of the added Cassandra
        clusters seed host.
  * Adds a new cluster to the service, and returns the newly added cluster object,
    if the operation was successful.

* PUT     /cluster/{cluster_name}
  * Expected query parameters:
      * *seedHost*: New host name or IP address used as Cassandra cluster seed.
  * Modifies a cluster's seed host. Comes in handy when the previous seed has left the cluster.

* DELETE  /cluster/{cluster_name}
  * Expected query parameters: *None*
  * Delete a cluster object identified by the given "cluster_name" path parameter.
    Cluster will get deleted only if there are no schedules or repair runs for the cluster,
    or the request will fail. Delete repair runs and schedules first before calling this.

## Repair Run Resource

* GET     /repair_run
  * Optional query parameters:
    * *state*: Comma separated list of repair run state names. Only names found in
    com.spotify.reaper.core.RunState are accepted.
  * Returns a list of repair runs, optionally fetching only the ones with *state* state.

* GET     /repair_run/{id}
  * Expected query parameters: *None*
  * Returns a repair run object identified by the given "id" path parameter.

* GET     /repair_run/cluster/{cluster_name} (com.spotify.reaper.resources.RepairRunResource)
  * Expected query parameters: *None*
  * Returns a list of all repair run statuses found for the given "cluster_name" path parameter.

* POST    /repair_run
  * Expected query parameters:
    * *clusterName*: Name of the Cassandra cluster.
    * *keyspace*: The name of the table keyspace.
    * *tables*: The name of the targeted tables (column families) as comma separated list.
                If no tables given, then the whole keyspace is targeted. (Optional)
    * *owner*: Owner name for the run. This could be any string identifying the owner.
    * *cause*: Identifies the process, or cause the repair was started. (Optional)
    * *segmentCount*: Defines the amount of segments to create for repair run. (Optional)
    * *repairParallelism*: Defines the used repair parallelism for repair run. (Optional)
    * *intensity*: Defines the repair intensity for repair run. (Optional)

* PUT    /repair_run/{id}
  * Expected query parameters:
    * *state*: New value for the state of the repair run.
      Possible values for given state are: "PAUSED" or "RUNNING".
  * Starts, pauses, or resumes a repair run identified by the "id" path parameter.
  * Can also be used to reattempt a repair run in state "ERROR", picking up where it left off.

* DELETE  /repair_run/{id}
  * Expected query parameters:
    * *owner*: Owner name for the run. If the given owner does not match the stored owner,
               the delete request will fail.
  * Delete a repair run object identified by the given "id" path parameter.
    Repair run and all the related repair segments will be deleted from the database.

## Repair Schedule Resource

* GET     /repair_schedule
  * Expected query parameters:
      * *clusterName*: Filter the returned schedule list based on the given
        cluster name. (Optional)
      * *keyspaceName*: Filter the returned schedule list based on the given
        keyspace name. (Optional)
  * Returns all repair schedules present in the Reaper

* GET     /repair_schedule/{id}
  * Expected query parameters: *None*
  * Returns a repair schedule object identified by the given "id" path parameter.

* POST    /repair_schedule
  * Expected query parameters:
    * *clusterName*: Name of the Cassandra cluster.
    * *keyspace*: The name of the table keyspace.
    * *tables*: The name of the targeted tables (column families) as comma separated list.
                If no tables given, then the whole keyspace is targeted. (Optional)
    * *owner*: Owner name for the schedule. This could be any string identifying the owner.
    * *segmentCount*: Defines the amount of segments to create for scheduled repair runs. (Optional)
    * *repairParallelism*: Defines the used repair parallelism for scheduled repair runs. (Optional)
    * *intensity*: Defines the repair intensity for scheduled repair runs. (Optional)
    * *scheduleDaysBetween*: Defines the amount of days to wait between scheduling new repairs.
                             For example, use value 7 for weekly schedule, and 0 for continuous.
    * *scheduleTriggerTime*: Defines the time for first scheduled trigger for the run.
                             If you don't give this value, it will be next mid-night (UTC).
                             Give date values in ISO format, e.g. "2015-02-11T01:00:00". (Optional)

* DELETE  /repair_schedule/{id}
  * Expected query parameters:
    * *owner*: Owner name for the schedule. If the given owner does not match the stored owner,
               the delete request will fail.
  * Delete a repair schedule object identified by the given "id" path parameter.
    Repair schedule will get deleted only if there are no associated repair runs for the schedule.
    Delete all the related repair runs before calling this endpoint.
