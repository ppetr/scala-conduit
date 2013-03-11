# scala-conduit

_Currently the library is experimental and can change in the future._

[![Build Status](https://secure.travis-ci.org/ppetr/scala-conduit.png?branch=master)](https://travis-ci.org/ppetr/scala-conduit)

## Overview

A `Pipe` is a component that receives objects of a given type and responds with objects of another type. It is similar to [UNIX pipelines](https://en.wikipedia.org/wiki/Unix_pipeline), except that instead of receiving and sending bytes it receives and sends specific objects. When a pipe finishes processing, it also produces a final result.

There are two main ways how to compose pipes:

- Sequence them. When the first pipe finishes processing, the second pipe continues. It's similar to simple sequencing of commands in a UNIX pipeline.
- Fuse them. The output of the first pipe (called upstream) is used as the input for the second one (called downstream). And if the first pipe finishes processing, the second pipe also receives its final result. This is similar to combining UNIX pipelines with `|`.

Unlike UNIX pipelines, when a pipeline is run, it is executed in a single thread and values are only generated on demand. A downstream pipe is only invoked when an upstream pipe requests input and is suspended again when it produces the requested value. So if an upstream pipe requests no input at all, the downstream pipe is never invoked.

This library aims at deterministic and correct handling of resources. Each pipe operation has a finalizer that is executed when the processing is interrupted for whatever reason.

The library is conceptually similar to Haskell's [conduit](http://hackage.haskell.org/package/conduit) library.

Requires Scala 2.10.

## Core concepts

TODO

## Examples

See Util.scala, IO.scala and NIO.scala for full code.

### Read a file and output its contents as `String` lines.

First we create a source pipe that takes no input and lists a given file as its output:

```scala
def readLines(r: BufferedReader): Source[String,Unit] = {
  implicit val fin = closeFin(r);
  untilF[Any,Any,String](Option(r.readLine).map(respond[String] _));
}
```

Note the implicit finalizer `fin`. Using Scala's `implicit` feature it is applied to all pipe operations so that the reader is closed properly when the pipe is interrupted. Here `closeFin` is simply

```scala
implicit def closeFin(implicit c: Closeable): Finalizer =
  Finalizer { System.err.println("Closing " + c); c.close() }
```

Using `readLines` we can construct a pipe that takes `BufferedReader`s at its input and outputs their contents:

```scala
val readLines: Pipe[BufferedReader,String,Unit] =
  unfold[BufferedReader,String](readLines _);
```

Finally, we compose it with a simple mapping pipe that converts `File`s into `BufferedReader`s:

```scala
val readLinesFile: Pipe[File,String,Unit] = {
  import Finalizer.empty
  mapF((f: File) => new BufferedReader(
                      new InputStreamReader(
                        new FileInputStream(f), "UTF-8"))
    ) >-> readLines
}
```



### Display the contents of all Scala files in the current directory

```scala
runPipe(
  listRec(new File(".")) >->
  filter[File](_.getName().endsWith(".scala")) >->
  readLinesFile >->
  log
)
```

where 

```scala
def listRec(dir: File): Source[File,Unit] =
```

is a source pipe that doesn't take any input and outputs recursively a list of all files in a directory (similar to UNIX [find](https://en.wikipedia.org/wiki/Find)),

```scala
def filter[A,R](p: A => Boolean): GenPipe[R,A,A,R] = ...
```

is a pipe that checks its input values and passes trough only those that satisfy a given predicate,

```scala
val readLines: Pipe[BufferedReader,String,Unit] = ...
```
  
`readLinesFile` is a pipe that for each `File` received on its input outputs all its lines as `String`s,

```scala
def writeLines(w: Writer): Sink[String,Unit] = ...
val log = writeLines(new OutputStreamWriter(System.out));
```
    

`log` receives `String`s and prints them to [standard output](https://en.wikipedia.org/wiki/Standard_output#Standard_output_.28stdout.29).


# Copyright

Copyright 2013, Petr Pudlák

Contact: [petr.pudlak.name](http://petr.pudlak.name/).

![LGPLv3](https://www.gnu.org/graphics/lgplv3-88x31.png)

This program is free software: you can redistribute it and/or modify it under
the terms of the GNU Lesser General Public License as published by the Free
Software Foundation, either version 3 of the License, or (at your option) any
later version.

This program is distributed in the hope that it will be useful, but WITHOUT ANY
WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
PARTICULAR PURPOSE.  See the GNU Lesser General Public License for more
details.

You should have received a copy of the GNU Lesser General Public License along
with this program.  If not, see <http://www.gnu.org/licenses/>.

