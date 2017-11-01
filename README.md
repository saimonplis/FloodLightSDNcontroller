Floodlight OpenFlow Controller with Packet Flow Admission Control 
=================================================================

Build Status:
-------------

[![Build Status](https://travis-ci.org/floodlight/floodlight.svg?branch=master)](https://travis-ci.org/floodlight/floodlight)

Floodlight Wiki
---------------

First, the Floodlight wiki has moved. Please visit  at new site hosted by Atlassian:

https://floodlight.atlassian.net/wiki/display/floodlightcontroller/Floodlight+Documentation

What is Floodlight?
-------------------

Floodlight is the leading open source SDN controller. It is supported by a community of developers including a number of engineers from Big Switch Networks (http://www.bigswitch.com/).

For further details visit the GitHub page of Floodlight: 

For instructions how to install Floodlight: https://floodlight.atlassian.net/wiki/spaces/floodlightcontroller/pages/1343544/Installation+Guide

Floodlight software architecture is composed by several number of modules. An application module is Learning switch that is a common L2 learning switch, which learns devices connected to its ports.
This Open Flow controller can work with a network hypervisor(such as FlowVisor).
We put some changes on this module to achieve our goals:
1) Packet flow prioritization
2) Bandwidth allocation for each network slice(chosen at FlowVisor level)

To use this controller, you need:
1) Open Flow Switch enabled(virtual o physic)
2) A network topology(phisical or virtual, such as Mininet)
3) To reach the main purpose, even a Network Hypervisor

If you use a Network Hypervisor you need one FloodLight controller for each slice.
Users run the controllers in the same machine, so you need to change the OpenFlow Port, HTTP ports and other ports. For a deepth guide, download the tutorial to run multiple FloodLight controllers with FlowVisor: http://www.mediafire.com/file/8rc7hcvpc258wm4/How+to+Run+Multiple+Floodlight+Controllers+with+FlowVisor.pdf.

Controller parameters setting up:
---------------------------------
Setting up of Open Flow Controller parameters for each network slice.
Go to the directory floodlight/src/main/resources/learningswith.defaultproperties and
you can parametrize the following parameters:
Allocated bandwidth: setting up of bandwidth allocated for a given slice
threshold: decide your threshold for bandwidth allocated
timeout: set the timeout value for flow rules inside the switch flow table

Each flow rule on Flow Table switch can have a timeout, after that time the rule expires and  packets incoming are treated as new packet.
If you don't need timer,you'll set timeout=0.

For further details,contact me:

e-mail: simoagliano@gmail.com





