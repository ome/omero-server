[![Actions Status](https://github.com/ome/omero-server/workflows/Gradle/badge.svg)](https://github.com/ome/omero-server/actions)

# omero-server

A Gradle project

## Usage

Like any Java project, include the jar into your classpath.

## Build from source

The compilation, testing, launch, and delivery of the application are
automated by means of a Gradle (https://gradle.org/) build file.
In order to perform a build, all you need is
a JDK -- version 11 and Gradle 6.8.x.
Clone this GitHub repository `git clone https://github.com/ome/omero-server.git`.
Go to the directory and enter:

  gradle build

This will compile, build, test and create a distribution bundle.

## Unit tests
 * Run `gradle test`
